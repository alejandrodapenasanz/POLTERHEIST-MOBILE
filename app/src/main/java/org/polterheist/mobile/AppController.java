package org.polterheist.mobile;

import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import androidx.mediarouter.media.MediaRouter;
import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
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
    private final ExecutorService probes = Executors.newFixedThreadPool(2);
    private final List<Runnable> listeners = new ArrayList<>();
    public final List<DlnaDevice> devices = new ArrayList<>();
    /** TVs detected outside the LAN path (Bluetooth, Wi-Fi Direct) or via mDNS without DLNA. */
    public final List<NearbyDevice> nearby = new ArrayList<>();
    public WifiDirectController wifiDirect;
    public BluetoothTvDiscovery bluetooth;
    private MdnsDiscovery mdns;
    private HotspotController hotspot;
    public String hotspotInfo = "";
    public boolean nearbyScanning;
    private String bridgeName;
    private final Set<String> probedHosts = Collections.synchronizedSet(new HashSet<>());
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
        initializeNearby();
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
        List<LocalNetwork> all = LocalNetwork.findAll(context);
        if (all.isEmpty()) networkLabel = "Sin red local · búsqueda por Bluetooth y Wi-Fi Direct disponible";
        else {
            StringBuilder label = new StringBuilder();
            for (LocalNetwork n : all) label.append(label.length() == 0 ? "" : "  +  ").append(n.label).append(" · ").append(n.address.getHostAddress());
            networkLabel = label.toString();
        }
        notifyUi();
    }

    /**
     * Full search when {@code query} is null: SSDP on every local path (Wi-Fi, Ethernet, Wi-Fi Direct, phone hotspot),
     * mDNS, Wi-Fi Direct peers and Bluetooth (the last two only with runtime permission).
     * With a query (IP, CIDR or range, any private subnet): unicast SSDP + known description URLs.
     */
    public void scan(String query) {
        if (scanning) return;
        List<Inet4Address> targets = null;
        if (query != null) {
            try { targets = IpRange.parse(query); }
            catch (IllegalArgumentException e) { message(e.getMessage()); return; }
        }
        List<LocalNetwork> paths = LocalNetwork.findAll(context);
        LocalNetwork routed = null;
        if (targets != null) {
            try { routed = LocalNetwork.forPeer(context, targets.get(0)); }
            catch (IOException e) { message("Para buscar por IP conecta el móvil a alguna red: Wi-Fi, Ethernet, Wi-Fi Direct o la red del móvil."); return; }
        }
        scanning = true;
        int scan = ++scanGeneration;
        probedHosts.clear();
        if (targets == null) { devices.clear(); nearby.clear(); }
        scanNote = targets == null ? "Buscando receptores en todas las redes y cerca del móvil…"
                : "Buscando en " + query + (routed.contains(targets.get(0)) ? "" : " (otra red, a través del router)") + "…";
        refreshNetwork();
        if (cast != null) cast.setDiscovering(true);
        if (targets == null) startNearbyScan(scan, !paths.isEmpty());
        notifyUi();
        final List<Inet4Address> chosenTargets = targets;
        final LocalNetwork chosenPath = routed;
        discovery.execute(() -> {
            List<String> warnings = Collections.synchronizedList(new ArrayList<>());
            java.util.function.Consumer<DlnaDevice> onFound = device -> main.post(() -> addDevice(scan, device));
            if (chosenTargets != null) {
                try { SsdpDiscovery.scan(context, chosenPath, chosenTargets, onFound, warnings::add); }
                catch (Exception e) { warnings.add("No se pudo completar la búsqueda: " + reason(e)); }
            } else if (!paths.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, paths.size()));
                List<Future<?>> all = new ArrayList<>();
                for (LocalNetwork path : paths) all.add(pool.submit(() -> {
                    try { SsdpDiscovery.scan(context, path, (List<InetAddress>) null, onFound, warnings::add); }
                    catch (Exception e) { warnings.add("DLNA en " + path.label + ": " + reason(e)); }
                }));
                for (Future<?> f : all) { try { f.get(); } catch (Exception ignored) { } }
                pool.shutdownNow();
            }
            String note;
            if (!warnings.isEmpty()) note = warnings.get(0);
            else if (chosenTargets == null && paths.isEmpty()) note = "Sin red local: se busca por Bluetooth y Wi-Fi Direct. También puedes crear la red del móvil.";
            else note = "";
            main.post(() -> {
                if (scan != scanGeneration) return;
                scanning = false;
                String hint = NearbyPermissions.missing(context).length > 0
                        ? " Concede los permisos de dispositivos cercanos para buscar también por Wi-Fi Direct y Bluetooth." : "";
                scanNote = (note.isEmpty() ? "Búsqueda terminada. Si falta tu TV, prueba otra red, Wi-Fi Direct o las otras formas de conexión." : note) + hint;
                if (cast != null) cast.setDiscovering(false);
                notifyUi();
            });
        });
    }

    private void addDevice(int scan, DlnaDevice device) {
        if (scan != scanGeneration) return;
        if (devices.stream().noneMatch(d -> d.id.equals(device.id) && d.host.equals(device.host))) devices.add(device);
        // A playable path replaces the "detected only" mDNS entry of the same host.
        nearby.removeIf(n -> n.source == NearbyDevice.Source.MDNS && n.address.equals(device.host));
        notifyUi();
    }

    // ---------------------------------------------------------------- Nearby (outside the LAN)

    private void initializeNearby() {
        wifiDirect = new WifiDirectController(context, new WifiDirectController.Listener() {
            @Override public void peers(List<WifiP2pDevice> peers) { onWifiDirectPeers(peers); }
            @Override public void connected(InetAddress groupOwner, boolean phoneIsOwner, String peerName) { onWifiDirectConnected(groupOwner, phoneIsOwner, peerName); }
            @Override public void disconnected() { onWifiDirectDisconnected(); }
            @Override public void status(String text) { message(text); }
        });
        bluetooth = new BluetoothTvDiscovery(context);
        mdns = new MdnsDiscovery(context);
        hotspot = new HotspotController(context);
    }

    private void startNearbyScan(int scan, boolean lan) {
        if (lan) mdns.start((name, type, host) -> onMdns(scan, name, type, host), 8000);
        if (wifiDirect.available() && NearbyPermissions.wifi(context)) wifiDirect.discover();
        boolean playingAudio = context.getApplicationContext() instanceof PolterApp
                && ((PolterApp) context.getApplicationContext()).audioActive();
        if (!playingAudio && bluetooth.available() && NearbyPermissions.bluetoothScan(context) && NearbyPermissions.bluetoothConnect(context)) {
            nearbyScanning = true;
            bluetooth.start(new BluetoothTvDiscovery.Listener() {
                @Override public void found(BluetoothDevice device, String name, String detail) {
                    if (scan != scanGeneration) return;
                    String key = "bt|" + device.getAddress();
                    if (nearby.stream().noneMatch(n -> n.key.equals(key))) nearby.add(new NearbyDevice(NearbyDevice.Source.BLUETOOTH, key, name, detail, device.getAddress(), device));
                    notifyUi();
                }
                @Override public void finished(String note) {
                    nearbyScanning = false;
                    if (!note.isEmpty() && scan == scanGeneration) scanNote = (scanNote.isEmpty() ? "" : scanNote + "\n") + note;
                    notifyUi();
                }
            }, 12000);
        }
    }

    private void onMdns(int scan, String name, String type, InetAddress host) {
        if (scan != scanGeneration) return;
        String ip = host.getHostAddress();
        if (devices.stream().anyMatch(d -> d.host.equals(ip))) return;
        String label = MdnsDiscovery.label(type);
        if (nearby.stream().noneMatch(n -> n.source == NearbyDevice.Source.MDNS && n.address.equals(ip))) {
            nearby.add(new NearbyDevice(NearbyDevice.Source.MDNS, "mdns|" + ip, name, label + " · " + ip, ip, type));
            notifyUi();
        }
        probeHost(scan, host);
    }

    /** Targeted DLNA probe of one host (unicast SSDP + well-known description URLs) on whichever path reaches it. */
    private void probeHost(int scan, InetAddress host) {
        if (!probedHosts.add(host.getHostAddress())) return;
        probes.execute(() -> {
            try {
                LocalNetwork path = LocalNetwork.forPeer(context, host);
                SsdpDiscovery.scan(context, path, List.of(host), device -> main.post(() -> addDevice(scan, device)), text -> { });
            } catch (Exception ignored) { }
        });
    }

    private void onWifiDirectPeers(List<WifiP2pDevice> peers) {
        nearby.removeIf(n -> n.source == NearbyDevice.Source.WIFI_DIRECT);
        for (WifiP2pDevice peer : peers) {
            if (!WifiDirectController.looksLikeTv(peer)) continue;
            nearby.add(new NearbyDevice(NearbyDevice.Source.WIFI_DIRECT, "p2p|" + peer.deviceAddress, peer.deviceName,
                    WifiDirectController.describe(peer), peer.deviceAddress, peer));
            if (bridgeName != null && NearbyDevice.sameTv(peer.deviceName, bridgeName)) {
                bridgeName = null; main.removeCallbacks(bridgeTimeout);
                wifiDirect.connect(peer);
            }
        }
        notifyUi();
    }

    public void connectWifiDirect(WifiP2pDevice peer) {
        if (!canChangeTarget()) return;
        if (!NearbyPermissions.wifi(context)) { message("Concede el permiso de dispositivos Wi-Fi cercanos para usar Wi-Fi Direct."); return; }
        wifiDirect.connect(peer);
    }
    public void disconnectWifiDirect() {
        if (sharing && network != null && network.kind == LocalNetwork.Kind.WIFI_DIRECT) stop();
        wifiDirect.disconnect();
    }
    public boolean wifiDirectConnected() { return wifiDirect != null && wifiDirect.connected(); }
    public String wifiDirectName() { return wifiDirect == null ? "" : wifiDirect.connectedName; }

    private void onWifiDirectConnected(InetAddress groupOwner, boolean phoneIsOwner, String peerName) {
        message("Conectado por Wi-Fi Direct con " + peerName + ". Buscando DLNA en la conexión directa…");
        scanWifiDirect(groupOwner, phoneIsOwner, 0);
    }
    /** The p2p interface can take a moment to get its IPv4 after the group forms: retry a few times. */
    private void scanWifiDirect(InetAddress groupOwner, boolean phoneIsOwner, int attempt) {
        LocalNetwork path = LocalNetwork.ofKind(context, LocalNetwork.Kind.WIFI_DIRECT);
        if (path == null) {
            if (attempt < 6) main.postDelayed(() -> scanWifiDirect(groupOwner, phoneIsOwner, attempt + 1), 1000);
            else message("Wi-Fi Direct conectado, pero Android no mostró su dirección IP. Prueba Buscar TVs.");
            return;
        }
        refreshNetwork();
        int scan = scanGeneration;
        int before = devices.size();
        probes.execute(() -> {
            java.util.function.Consumer<DlnaDevice> onFound = device -> main.post(() -> addDevice(scan, device));
            try {
                if (!phoneIsOwner) SsdpDiscovery.scan(context, path, List.of(groupOwner), onFound, t -> { });
                SsdpDiscovery.scan(context, path, (List<InetAddress>) null, onFound, t -> { });
            } catch (Exception ignored) { }
            main.post(() -> {
                boolean found = devices.stream().anyMatch(d -> path.contains(safeAddress(d.host)));
                if (found) message("TV disponible por Wi-Fi Direct. Selecciónala en la lista y pulsa Enviar a la TV.");
                else if (devices.size() == before) message("La TV está conectada por Wi-Fi Direct pero no ofrece DLNA. Usa «Usar el navegador de la TV»: el enlace funcionará por Wi-Fi Direct.");
                refreshNetwork();
            });
        });
    }
    private void onWifiDirectDisconnected() {
        if (sharing && network != null && network.kind == LocalNetwork.Kind.WIFI_DIRECT) networkLost();
        devices.removeIf(d -> "Wi-Fi Direct".equals(d.via));
        if (target == Target.DLNA && dlna != null && "Wi-Fi Direct".equals(dlna.via) && !sharing) { target = Target.NONE; dlna = null; targetName = "Ninguna TV seleccionada"; }
        message("Wi-Fi Direct desconectado.");
        refreshNetwork();
    }

    /** Bluetooth only detects: look for the same TV over Wi-Fi Direct and connect there. */
    public void bridgeBluetooth(NearbyDevice tv) {
        if (!canChangeTarget()) return;
        if (!wifiDirect.available()) { message("Este móvil no admite Wi-Fi Direct. Usa la red del móvil o duplicar pantalla para esta TV."); return; }
        if (!NearbyPermissions.wifi(context)) { message("Concede el permiso de dispositivos Wi-Fi cercanos para conectar por Wi-Fi Direct."); return; }
        for (WifiP2pDevice peer : wifiDirect.peers) if (NearbyDevice.sameTv(peer.deviceName, tv.name)) { wifiDirect.connect(peer); return; }
        bridgeName = tv.name;
        message("Buscando " + tv.name + " por Wi-Fi Direct… En la TV activa Wi-Fi Direct, Screen Mirroring o Compartir pantalla.");
        wifiDirect.discover();
        main.removeCallbacks(bridgeTimeout);
        main.postDelayed(bridgeTimeout, 20000);
    }
    private final Runnable bridgeTimeout = () -> {
        if (bridgeName == null) return;
        String name = bridgeName; bridgeName = null;
        List<NearbyDevice> tvs = new ArrayList<>();
        for (NearbyDevice n : nearby) if (n.source == NearbyDevice.Source.WIFI_DIRECT) tvs.add(n);
        String hint = tvs.size() == 1 ? " Puede que sea «" + tvs.get(0).name + "» en la lista Wi-Fi Direct." : "";
        message("No se encontró " + name + " por Wi-Fi Direct." + hint + " Alternativas: crea la red del móvil y conecta la TV a ella, o usa duplicar pantalla.");
    };
    public boolean pairBluetooth(NearbyDevice tv) {
        boolean ok = tv.handle instanceof BluetoothDevice && bluetooth.pair((BluetoothDevice) tv.handle);
        message(ok ? "Emparejando por Bluetooth. Acepta en la TV. El vídeo seguirá necesitando Wi-Fi Direct o una red." : "No se pudo emparejar por Bluetooth.");
        return ok;
    }
    public void reprobe(NearbyDevice tv) {
        probedHosts.remove(tv.address);
        try { probeHost(scanGeneration, InetAddress.getByName(tv.address)); message("Comprobando DLNA en " + tv.address + "…"); }
        catch (IOException e) { message(reason(e)); }
    }

    /** mDNS entries already covered by a Cast route are hidden to avoid duplicates. */
    public List<NearbyDevice> visibleNearby() {
        List<NearbyDevice> result = new ArrayList<>();
        for (NearbyDevice n : nearby) {
            if (n.source == NearbyDevice.Source.MDNS && cast != null && cast.routes.stream().anyMatch(r -> NearbyDevice.sameTv(r.getName(), n.name))) continue;
            result.add(n);
        }
        return result;
    }

    // ---------------------------------------------------------------- Phone hotspot

    public void startHotspot() {
        hotspot.start(new HotspotController.Listener() {
            @Override public void started(String ssid, String password) {
                hotspotInfo = "Red: " + ssid + "\nClave: " + password;
                message("Red del móvil creada. En la TV, conéctala al Wi-Fi «" + ssid + "» y pulsa Buscar TVs.");
                refreshNetwork();
            }
            @Override public void stopped(String reason) {
                if (sharing && network != null && network.kind == LocalNetwork.Kind.HOTSPOT) networkLost();
                hotspotInfo = "";
                message(reason);
                refreshNetwork();
            }
        });
    }
    public void stopHotspot() {
        if (sharing && network != null && network.kind == LocalNetwork.Kind.HOTSPOT) stop();
        hotspot.stop(); hotspotInfo = "";
        message("Red del móvil apagada.");
        refreshNetwork();
    }
    public boolean hotspotActive() { return hotspot != null && hotspot.active(); }

    /** Path the media server must listen on so the selected target can reach the phone. */
    private LocalNetwork networkFor(Target selected) throws IOException {
        if (selected == Target.DLNA) return LocalNetwork.forPeer(context, dlna.host);
        if (selected == Target.BROWSER) {
            if (wifiDirectConnected()) { LocalNetwork p = LocalNetwork.ofKind(context, LocalNetwork.Kind.WIFI_DIRECT); if (p != null) return p; }
            if (hotspotActive()) {
                LocalNetwork h = LocalNetwork.ofKind(context, LocalNetwork.Kind.HOTSPOT);
                if (h != null) return h;
                for (LocalNetwork n : LocalNetwork.findAll(context)) if (n.network == null && n.kind != LocalNetwork.Kind.WIFI_DIRECT) return n;
            }
            List<LocalNetwork> all = LocalNetwork.findAll(context);
            if (!all.isEmpty()) return all.get(0);
        }
        return LocalNetwork.find(context);
    }
    private static InetAddress safeAddress(String host) {
        try { return InetAddress.getByName(host); } catch (IOException e) { return null; }
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
        message("TV DLNA seleccionada" + (device.via.isEmpty() ? "" : " (" + device.via + ")") + ". Pulsa Enviar a la TV.");
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
        try { network = networkFor(target); }
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
