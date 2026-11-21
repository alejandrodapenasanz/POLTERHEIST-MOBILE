package org.polterheist.mobile;

import android.content.*;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import androidx.mediarouter.media.MediaRouter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** UI state lives across Activity recreation; active sharing is owned by ShareService. */
public final class AppController {
    public enum Target { NONE, CAST, DLNA, BROWSER }
    private final Context context;
    public final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService discovery = Executors.newSingleThreadExecutor();
    private final List<Runnable> listeners = new ArrayList<>();
    public final List<DlnaDevice> devices = new ArrayList<>();
    public CastController cast;
    public MediaFile media;
    private MediaFile castPhoto;
    public Target target = Target.NONE;
    public String targetName = "Ninguna TV seleccionada";
    public String status = "Elige tu contenido y busca una TV";
    public String networkLabel = "Comprobando conexión…";
    public String scanNote = "";
    public boolean scanning, busy, sharing, paused, loop;
    public DlnaDevice dlna;
    public LocalNetwork network;
    private MediaHttpServer server;
    private int generation;
    private int scanGeneration;
    private boolean stopping;
    private int pollingErrors;

    public AppController(Context context) {
        this.context = context;
        // Only app-owned temporary media; no user library files are removed.
        io.execute(() -> {
            File[] files = new File(context.getCacheDir(), "selected-media").listFiles();
            if (files != null) for (File file : files) if (file.isFile()) file.delete();
        });
        refreshNetwork();
    }

    public void initializeCast() {
        if (cast != null) return;
        cast = new CastController(context, new CastController.Listener() {
            @Override public void changed() { notifyUi(); }
            @Override public void session(boolean connected, String name) {
                if (target != Target.CAST) return;
                if (connected) { targetName = name; status = "TV conectada. Pulsa Enviar a la TV."; }
                else {
                    if (sharing && !stopping) finishSharing();
                    status = name;
                }
                notifyUi();
            }
            @Override public void status(String text) {
                if (target == Target.CAST && !stopping) { status = text; notifyUi(); }
            }
        });
    }

    public void addListener(Runnable listener) { listeners.add(listener); listener.run(); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void notifyUi() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    public void message(String text) { status = text; notifyUi(); }
    public void refreshNetwork() {
        try { LocalNetwork n = LocalNetwork.find(context); networkLabel = n.label + " · " + n.address.getHostAddress(); }
        catch (IOException e) { networkLabel = "Sin conexión Wi-Fi local"; }
        notifyUi();
    }

    public void scan(String ip) {
        if (scanning) return;
        LocalNetwork lan;
        try { lan = LocalNetwork.find(context); }
        catch (IOException e) { message(e.getMessage()); return; }
        if (ip != null && !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) { message("Introduce una IPv4, por ejemplo 192.168.1.50"); return; }
        scanning = true;
        int scan = ++scanGeneration;
        devices.clear(); scanNote = "Buscando receptores compatibles…";
        refreshNetwork();
        if (cast != null) cast.setDiscovering(true);
        notifyUi();
        discovery.execute(() -> {
            String[] warning = {""};
            try {
                SsdpDiscovery.scan(context, lan, ip, device -> main.post(() -> {
                    if (scan != scanGeneration || !scanning) return;
                    if (devices.stream().noneMatch(d -> d.id.equals(device.id) && d.host.equals(device.host))) devices.add(device);
                    notifyUi();
                }), text -> warning[0] = text);
            } catch (Exception e) { warning[0] = "No se pudo completar DLNA: " + reason(e); }
            String note = warning[0];
            main.post(() -> {
                if (scan != scanGeneration) return;
                scanning = false;
                scanNote = note.isEmpty() ? "Búsqueda terminada. Si falta tu TV, prueba las otras formas de conexión." : note;
                if (cast != null) cast.setDiscovering(false);
                notifyUi();
            });
        });
    }

    public void importMedia(Uri uri) {
        if (sharing || busy) { message("Detén la emisión antes de cambiar el archivo."); return; }
        busy = true; message("Preparando el archivo en el móvil…");
        io.execute(() -> {
            try {
                MediaFile prepared = MediaImporter.prepare(context, uri);
                main.post(() -> {
                    if (media != null) media.file.delete();
                    if (castPhoto != null) castPhoto.file.delete();
                    media = prepared; castPhoto = null; busy = false;
                    message(media.isImage() ? "Imagen lista para enviar" : "Vídeo listo. La compatibilidad depende de los códecs de la TV.");
                });
            } catch (Exception e) { main.post(() -> { busy = false; message("No se pudo abrir: " + reason(e)); }); }
        });
    }

    public boolean canChangeTarget() {
        if (sharing || busy) { message("Detén la emisión antes de cambiar de TV."); return false; }
        return true;
    }
    public void chooseDlna(DlnaDevice device) {
        if (!canChangeTarget()) return;
        if (!device.playable()) { message("Dispositivo detectado, pero no ofrece los controles necesarios. Prueba el navegador o duplicar pantalla."); return; }
        if (target == Target.CAST && cast != null) cast.stop();
        target = Target.DLNA; dlna = device; targetName = device.name;
        message("TV DLNA seleccionada. Pulsa Enviar a la TV.");
    }
    public void chooseCast(MediaRouter.RouteInfo route) {
        if (!canChangeTarget()) return;
        target = Target.CAST; targetName = route.getName(); dlna = null;
        message("Conectando con " + targetName + "…");
        cast.connect(route);
    }
    public void chooseBrowser() {
        if (!canChangeTarget()) return;
        if (target == Target.CAST && cast != null) cast.stop();
        target = Target.BROWSER; targetName = "Navegador de la TV"; dlna = null;
        message("Pulsa Enviar a la TV para crear el enlace local.");
    }

    public String viewerUrl() { return server == null ? "" : server.viewerUrl(); }
    public boolean viewerConnected() { return server != null && server.hasViewer(); }

    public void send() {
        if (busy || sharing) return;
        if (media == null) { message("Elige primero una imagen o un vídeo."); return; }
        if (target == Target.NONE) { message("Selecciona una TV o el modo navegador."); return; }
        if (target == Target.CAST && (cast == null || !cast.connected())) { message("Espera a que se conecte la TV Cast, o selecciónala otra vez."); return; }
        if (target == Target.DLNA && loop && (media.isImage() || !dlna.actions.contains("SetPlayMode"))) {
            loop = false;
            message("Esta TV DLNA no anuncia repetición. Enviando sin bucle.");
        }
        try { network = LocalNetwork.find(context); }
        catch (IOException e) { message(e.getMessage()); return; }
        busy = true; sharing = true; paused = false; stopping = false; pollingErrors = 0;
        generation++;
        message("Preparando envío a " + targetName + "…");
        try { ContextCompat.startForegroundService(context, new Intent(context, ShareService.class)); }
        catch (RuntimeException e) { finishSharing(); message("Android no permitió iniciar la emisión: " + reason(e)); }
    }

    public void serviceReady() {
        if (!sharing || !busy) return;
        int operation = generation;
        MediaFile chosen = media;
        Target selectedTarget = target;
        LocalNetwork lan = network;
        boolean repeat = loop;
        io.execute(() -> {
            MediaHttpServer created = null;
            try {
                MediaFile transmitted = chosen;
                if (selectedTarget == Target.CAST && chosen.isImage()) {
                    if (castPhoto == null) castPhoto = ImageClipEncoder.encode(chosen);
                    transmitted = castPhoto;
                }
                String html;
                try (InputStream input = context.getAssets().open("receiver.html"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192]; int n;
                    while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
                    html = output.toString(StandardCharsets.UTF_8.name());
                }
                created = new MediaHttpServer(lan.address, html);
                String url = created.select(transmitted, repeat);
                MediaHttpServer ready = created;
                MediaFile sent = transmitted;
                main.post(() -> {
                    if (operation != generation || !sharing) { ready.close(); return; }
                    server = ready;
                    if (selectedTarget == Target.CAST) {
                        cast.load(sent, url, repeat || chosen.isImage(), error -> {
                            if (operation != generation) return;
                            busy = false;
                            if (error != null) { finishSharing(); message(error); }
                            else message("Envío aceptado por Cast. Esperando estado de reproducción…");
                        });
                    } else if (selectedTarget == Target.DLNA) {
                        io.execute(() -> {
                            try {
                                DlnaClient client = new DlnaClient(lan::open);
                                client.load(dlna, chosen, url);
                                String loopWarning = "";
                                if (!chosen.isImage() && dlna.actions.contains("SetPlayMode")) {
                                    try {
                                        LinkedHashMap<String,String> args = new LinkedHashMap<>();
                                        args.put("InstanceID", "0"); args.put("NewPlayMode", repeat ? "REPEAT_ONE" : "NORMAL");
                                        client.action(dlna, "SetPlayMode", args);
                                    } catch (Exception e) { if (repeat) loopWarning = " La TV no aceptó el bucle."; }
                                }
                                String note = loopWarning;
                                main.post(() -> {
                                    if (operation != generation) return;
                                    busy = false;
                                    if (!note.isEmpty()) loop = false;
                                    message("La TV aceptó el envío." + note);
                                    main.postDelayed(pollDlna, 2000);
                                });
                            } catch (Exception e) { main.post(() -> fail(operation, e)); }
                        });
                    } else {
                        busy = false;
                        message("Abre el enlace de abajo en el navegador de la TV.");
                    }
                });
            } catch (Exception e) {
                if (created != null) created.close();
                main.post(() -> fail(operation, e));
            }
        });
    }

    private void fail(int operation, Exception e) {
        if (operation != generation) return;
        finishSharing(); message("No se pudo enviar: " + reason(e));
    }
    public void togglePause() {
        if (!sharing || busy || media == null || media.isImage()) return;
        boolean next = !paused;
        int operation = generation;
        if (target == Target.BROWSER) {
            server.command(next ? "pause" : "play"); paused = next;
            message(next ? "Pausa solicitada al navegador" : "Reproducción solicitada al navegador");
        } else if (target == Target.CAST) {
            busy = true; notifyUi();
            cast.command(next, error -> {
                if (operation != generation) return;
                busy = false;
                if (error == null) paused = next;
                message(error == null ? (next ? "Pausa aceptada" : "Reproducción aceptada") : error);
            });
        } else if (target == Target.DLNA) {
            busy = true; notifyUi();
            io.execute(() -> {
                try {
                    DlnaClient client = new DlnaClient(network::open);
                    if (next) client.pause(dlna); else client.play(dlna);
                    main.post(() -> { if (operation == generation) { busy = false; paused = next; message(next ? "Pausa aceptada" : "Reproducción aceptada"); } });
                } catch (Exception e) { main.post(() -> { if (operation == generation) { busy = false; message(reason(e)); } }); }
            });
        }
    }
    public void setLoop(boolean value) {
        if (sharing) return;
        loop = value; notifyUi();
    }

    public void stop() {
        if (stopping) return;
        if (!sharing) { if (target == Target.CAST && cast != null) cast.stop(); return; }
        stopping = true; busy = true;
        ++generation;
        main.removeCallbacks(pollDlna);
        if (server != null) server.command("stop");
        message("Deteniendo emisión…");
        if (target == Target.DLNA && network != null) {
            DlnaDevice device = dlna; LocalNetwork lan = network;
            io.execute(() -> {
                String result = "Emisión detenida";
                try { new DlnaClient(lan::open).stop(device); }
                catch (Exception e) { result = "Envío local cerrado; la TV no confirmó Stop y puede conservar contenido en memoria."; }
                String text = result;
                main.post(() -> { finishSharing(); message(text); });
            });
        } else {
            if (target == Target.CAST && cast != null) cast.stop();
            // Give browser polling a chance to clear its image/video before closing HTTP.
            main.postDelayed(() -> { finishSharing(); message("Emisión detenida"); }, target == Target.BROWSER ? 1600 : 0);
        }
    }

    private void finishSharing() {
        generation++; sharing = false; busy = false; paused = false; stopping = false;
        main.removeCallbacks(pollDlna);
        if (server != null) { server.close(); server = null; }
        context.stopService(new Intent(context, ShareService.class));
        notifyUi();
    }
    public void serviceDestroyed() {
        if (sharing) {
            generation++; sharing = false; busy = false; paused = false; stopping = false;
            main.removeCallbacks(pollDlna);
            if (server != null) { server.close(); server = null; }
            message("Android cerró el envío local. Vuelve a enviar el archivo.");
        }
    }
    public void networkLost() {
        if (sharing) { stop(); message("Se perdió la red de la emisión. Vuelve a conectar el móvil y envía de nuevo."); }
        refreshNetwork();
    }
    private final Runnable pollDlna = new Runnable() {
        @Override public void run() {
            if (!sharing || target != Target.DLNA || dlna == null || !dlna.actions.contains("GetTransportInfo")) return;
            int operation = generation;
            DlnaDevice device = dlna; LocalNetwork lan = network;
            io.execute(() -> {
                try {
                    String state = new DlnaClient(lan::open).state(device);
                    main.post(() -> {
                        if (operation != generation) return;
                        pollingErrors = 0;
                        if (state.equals("PLAYING")) { paused = false; message("La TV confirma reproducción"); }
                        else if (state.equals("PAUSED_PLAYBACK")) { paused = true; message("La TV confirma pausa"); }
                        else if (state.equals("STOPPED")) message("La TV indica reproducción detenida");
                        main.postDelayed(this, 3000);
                    });
                } catch (Exception e) { main.post(() -> {
                    if (operation != generation) return;
                    if (++pollingErrors >= 3) message("No se puede consultar la TV. Comprueba que siga conectada.");
                    main.postDelayed(this, 5000);
                }); }
            });
        }
    };
    private static String reason(Exception e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? e.getClass().getSimpleName() : text.substring(0, Math.min(250, text.length()));
    }
}
