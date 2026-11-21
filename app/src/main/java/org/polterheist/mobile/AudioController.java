package org.polterheist.mobile;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread audio state; a mediaPlayback service owns each active player lifetime. */
public final class AudioController {
    private final Context context;
    private final AudioManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Runnable> listeners = new ArrayList<>();
    private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private AudioFocusRequest focus;
    private MediaPlayer player;
    private AudioPlaybackService service;
    private boolean prepared, focused, verifying, noisyRegistered;
    private long routeDeadline, resumePosition;
    private int importGeneration;
    private int focusGeneration;
    private Future<?> importTask;
    public final List<AudioDeviceInfo> outputs = new ArrayList<>();
    public AudioFile file;
    public int selectedId = -1;
    public String selectedName = "Ninguna salida elegida";
    public boolean importing, playing, preparing, loop;
    public String status = "Conecta un altavoz o unos auriculares y elige un audio.";
    private final Runnable routePoll = this::checkRoute;
    private final Runnable prepareTimeout = () -> fail("El archivo tardó demasiado en prepararse. Prueba otro audio.");

    public AudioController(Context context) {
        this.context = context.getApplicationContext(); manager = context.getSystemService(AudioManager.class);
        // Serial with imports: only remove temporary files left by an earlier process.
        io.execute(() -> {
            File[] files = new File(context.getCacheDir(),"selected-audio").listFiles();
            if (files != null) for (File f : files) if (f.isFile()) f.delete();
        });
        if (manager != null) manager.registerAudioDeviceCallback(new AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refreshOutputs(); }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refreshOutputs(); }
        },main);
        refreshOutputs();
    }
    public void addListener(Runnable r) { listeners.add(r); r.run(); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void changed() {
        if (service != null) service.update();
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }
    public void message(String text) { status = text; changed(); }
    public static boolean bluetoothOutput(AudioDeviceInfo d) {
        int t = d.getType();
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || (Build.VERSION.SDK_INT >= 28 && t == AudioDeviceInfo.TYPE_HEARING_AID)
                || (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER));
    }
    public static String outputLabel(AudioDeviceInfo d) {
        String name = d.getProductName() == null ? "Salida Bluetooth" : d.getProductName().toString();
        String profile = d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ? "A2DP"
                : Build.VERSION.SDK_INT >= 28 && d.getType() == AudioDeviceInfo.TYPE_HEARING_AID ? "Audífono" : "LE Audio";
        return name + " · " + profile;
    }
    public void refreshOutputs() {
        outputs.clear();
        if (manager != null && NearbyPermissions.bluetoothConnect(context)) {
            try { for (AudioDeviceInfo d : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) if (bluetoothOutput(d)) outputs.add(d); }
            catch (SecurityException ignored) { }
        }
        if (selectedId != -1 && selectedOutput() == null) {
            if (playing || preparing) pause("Salida Bluetooth desconectada. Vuelve a conectarla y pulsa Reproducir.");
            selectedId = -1; selectedName = "Ninguna salida elegida";
        }
        changed();
    }
    private AudioDeviceInfo selectedOutput() {
        for (AudioDeviceInfo d : outputs) if (d.getId() == selectedId) return d;
        return null;
    }
    public void selectOutput(int id) {
        if (id == selectedId) return;
        if (playing || preparing) pause("Salida cambiada. Pulsa Reproducir para continuar.");
        for (AudioDeviceInfo d : outputs) if (d.getId() == id) {
            selectedId = id; selectedName = outputLabel(d);
            message("Salida elegida: " + selectedName + ". Pulsa Reproducir."); return;
        }
        message("Esta salida ya no está conectada. Actualiza la conexión en Ajustes.");
    }
    public void importAudio(Uri uri) {
        if (importing) return;
        stop(); importing = true; int generation = ++importGeneration;
        message("Preparando una copia privada del audio…");
        importTask = io.submit(() -> {
            try {
                AudioFile next = AudioFile.copy(context,uri);
                main.post(() -> {
                    if (generation != importGeneration) { next.file.delete(); return; }
                    AudioFile previous = file; file = next; importing = false; importTask = null;
                    if (previous != null) io.execute(() -> previous.file.delete());
                    message("Audio listo. Elige la salida Bluetooth y pulsa Reproducir.");
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (generation != importGeneration) return;
                    importing = false; importTask = null;
                    message("No se pudo abrir el audio: " + (e.getMessage() == null ? "formato no compatible o acceso denegado" : e.getMessage()));
                });
            }
        });
    }
    public void cancelImport() {
        if (!importing) return;
        importGeneration++; importing = false;
        if (importTask != null) importTask.cancel(true);
        importTask = null; message("Importación cancelada.");
    }
    /** Called only by an explicit UI/media-session/notification play action. */
    public void play() {
        refreshOutputs();
        if (importing || preparing || playing) return;
        if (file == null) { message("Elige primero un archivo de audio."); return; }
        if (selectedOutput() == null) { message("Conecta y selecciona una salida Bluetooth antes de reproducir."); return; }
        try {
            ContextCompat.startForegroundService(context,new Intent(context,AudioPlaybackService.class).setAction(AudioPlaybackService.PLAY));
        } catch (RuntimeException e) { message("Android no permitió iniciar la reproducción. Abre la app y vuelve a pulsar Reproducir."); }
    }
    void attach(AudioPlaybackService service) { this.service = service; }
    void playFromService() {
        if (playing || preparing) return;
        refreshOutputs();
        if (file == null || importing || selectedOutput() == null) { fail("Selecciona un audio y una salida Bluetooth conectada."); return; }
        if (prepared && player != null) { startPlayer(); return; }
        releasePlayer(); preparing = true; status = "Preparando audio…";
        try {
            MediaPlayer p = new MediaPlayer(); player = p;
            p.setAudioAttributes(attributes); p.setWakeMode(context,PowerManager.PARTIAL_WAKE_LOCK);
            p.setDataSource(file.file.getPath()); p.setLooping(loop);
            if (Build.VERSION.SDK_INT >= 28) p.addOnRoutingChangedListener(r -> {
                if (player == p && playing) checkRoute();
            },main);
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;
                main.removeCallbacks(prepareTimeout); prepared = true; preparing = false; startPlayer();
            });
            p.setOnCompletionListener(mp -> {
                if (player != mp) return;
                if (verifying) fail("No se pudo confirmar la salida Bluetooth antes de terminar el audio.");
                else { stop(); message("Audio terminado."); }
            });
            p.setOnErrorListener((mp,what,extra) -> {
                if (player == mp) fail("No se pudo reproducir este archivo (" + what + "/" + extra + "). Prueba otro formato de audio.");
                return true;
            });
            main.postDelayed(prepareTimeout,15_000); p.prepareAsync(); changed();
        } catch (Exception e) { fail("No se pudo preparar el archivo. Vuelve a seleccionarlo o elige otro formato."); }
    }
    private void startPlayer() {
        AudioDeviceInfo output = selectedOutput();
        if (output == null || !NearbyPermissions.bluetoothConnect(context)) { fail("La salida Bluetooth ya no está disponible."); return; }
        try {
            if (Build.VERSION.SDK_INT >= 28 && !player.setPreferredDevice(output)) { fail("Android no aceptó esa salida. Conéctala desde los ajustes de Bluetooth."); return; }
            // API 26/27 lacks MediaPlayer routing introspection; use only system A2DP routing.
            if (Build.VERSION.SDK_INT < 28 && !manager.isBluetoothA2dpOn()) { fail("Activa Audio multimedia en los ajustes Bluetooth del accesorio."); return; }
            int focusRequest = ++focusGeneration;
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change -> {
                        if (focusRequest != focusGeneration) return;
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                            pause("Audio en pausa por otra aplicación o una llamada. Pulsa Reproducir para continuar.");
                    },main).build();
            focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!focused) { fail("Otra aplicación tiene el audio. Vuelve a pulsar Reproducir cuando termine."); return; }
            if (!noisyRegistered) {
                ContextCompat.registerReceiver(context,noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_EXPORTED);
                noisyRegistered = true;
            }
            resumePosition = player.getCurrentPosition();
            verifying = Build.VERSION.SDK_INT >= 28;
            player.setVolume(verifying ? 0f : 1f,verifying ? 0f : 1f);
            player.start(); playing = true; routeDeadline = SystemClock.elapsedRealtime()+3000;
            status = verifying ? "Comprobando la salida de audio…" : "Reproduciendo · ruta Bluetooth gestionada por Android";
            if (verifying) main.post(routePoll);
            changed();
        } catch (RuntimeException e) { fail("Android no pudo iniciar el audio. Revisa la salida Bluetooth y vuelve a intentarlo."); }
    }
    /** A preference is not proof of routing. Start muted, then allow sound only on the selected output. */
    private void checkRoute() {
        main.removeCallbacks(routePoll);
        if (!playing || player == null || Build.VERSION.SDK_INT < 28) return;
        try {
            AudioDeviceInfo actual = player.getRoutedDevice();
            boolean matches = actual != null && bluetoothOutput(actual) && actual.getId() == selectedId;
            if (matches) {
                if (verifying) {
                    verifying = false;
                    player.seekTo(resumePosition,MediaPlayer.SEEK_CLOSEST);
                    player.setVolume(1f,1f); status = "Reproduciendo en " + selectedName; changed();
                }
            } else {
                player.setVolume(0f,0f);
                if (!verifying || SystemClock.elapsedRealtime() >= routeDeadline) {
                    pause("Android cambió o no confirmó la salida elegida. Conecta el accesorio en Ajustes y vuelve a reproducir."); return;
                }
            }
            main.postDelayed(routePoll,verifying ? 100 : 500);
        } catch (RuntimeException e) { fail("Se perdió la salida de audio. Vuelve a conectarla."); }
    }
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause("Salida de audio desconectada. Reproducción en pausa."); }
    };
    public void pause(String note) {
        main.removeCallbacks(routePoll); main.removeCallbacks(prepareTimeout);
        if (preparing) releasePlayer();
        if (player != null && prepared) {
            try {
                player.setVolume(0f,0f);
                if (playing) player.pause();
                if (verifying) player.seekTo(resumePosition,MediaPlayer.SEEK_CLOSEST);
            }
            catch (RuntimeException e) { releasePlayer(); }
        }
        playing = false; preparing = false; verifying = false; abandonFocus(); status = note; changed();
        if (service != null) service.idle();
    }
    public void stop() {
        releasePlayer(); status = "Audio detenido."; changed();
        if (service != null) service.finishPlayback();
    }
    private void fail(String note) {
        releasePlayer(); status = note; changed();
        if (service != null) service.finishPlayback();
    }
    private void abandonFocus() {
        focusGeneration++;
        if (focused && manager != null && focus != null) manager.abandonAudioFocusRequest(focus);
        focused = false; focus = null;
        if (noisyRegistered) { context.unregisterReceiver(noisy); noisyRegistered = false; }
    }
    private void releasePlayer() {
        main.removeCallbacks(routePoll); main.removeCallbacks(prepareTimeout);
        MediaPlayer old = player; player = null;
        if (old != null) old.release();
        prepared = false; playing = false; preparing = false; verifying = false; abandonFocus();
    }
    void detached(AudioPlaybackService owner) {
        if (service != owner) return;
        service = null; boolean active = playing || preparing; releasePlayer();
        if (active) status = "El servicio de audio se ha detenido. Pulsa Reproducir para iniciar otra sesión.";
        changed();
    }
    public void setLoop(boolean value) {
        loop = value; if (player != null && prepared) player.setLooping(value); changed();
    }
    public long position() {
        try { return prepared && player != null ? player.getCurrentPosition() : 0; }
        catch (IllegalStateException e) { return 0; }
    }
    public long duration() {
        try { return prepared && player != null ? player.getDuration() : file == null ? 0 : file.durationMs; }
        catch (IllegalStateException e) { return 0; }
    }
    public void seek(long position) {
        if (!prepared || player == null || verifying) return;
        player.seekTo(Math.max(0,Math.min(position,duration())),MediaPlayer.SEEK_CLOSEST); changed();
    }
    public boolean canSeek() { return prepared && !verifying; }
}
