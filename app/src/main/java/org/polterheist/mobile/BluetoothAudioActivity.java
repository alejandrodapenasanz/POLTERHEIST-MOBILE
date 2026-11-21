package org.polterheist.mobile;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.Locale;

public final class BluetoothAudioActivity extends AppCompatActivity {
    private DeviceUi ui;
    private BluetoothDiscovery bluetooth;
    private AudioController audio;
    private LinearLayout deviceList, outputList;
    private TextView discoveryStatus, playbackStatus, fileName, outputName, time;
    private Button search, pick, play, pause, stop, cancelImport;
    private SeekBar position;
    private CheckBox repeat;
    private String deviceSignature = "", outputSignature = "";
    private boolean rendering, seeking, scanAfterEnable;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable discoveryListener = this::renderDevices, audioListener = this::renderAudio;
    private final Runnable clock = new Runnable() {
        @Override public void run() { renderPosition(); main.postDelayed(this,500); }
    };
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
        if (bluetooth.permitted()) { bluetooth.observe(); search(); }
        else bluetooth.message("Sin permiso no puedo buscar ni emparejar. Puedes concederlo desde Permisos de la app.");
        audio.refreshOutputs();
    });
    private final ActivityResultLauncher<Intent> enable = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        boolean pending = scanAfterEnable; scanAfterEnable = false;
        bluetooth.observe();
        if (bluetooth.enabled() && pending) bluetooth.start();
        else if (!bluetooth.enabled()) bluetooth.message("Bluetooth sigue apagado. Puedes activarlo desde los ajustes del sistema.");
    });
    private final ActivityResultLauncher<String[]> picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null) audio.importAudio(uri);
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setVolumeControlStream(AudioManager.STREAM_MUSIC);
        PolterApp app = (PolterApp)getApplication(); bluetooth = app.bluetooth(); audio = app.audio();
        if (app.controller.bluetooth != null) app.controller.bluetooth.stop();
        scanAfterEnable = state != null && state.getBoolean("scanAfterEnable");
        ui = new DeviceUi(this);
        ui.add(ui.body,ui.button("Inicio · todos los dispositivos",false,() -> {
            startActivity(new Intent(this,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish();
        }),0);
        ui.heading("Encuentra.\nConecta. Escucha.","Tus altavoces, auriculares y accesorios Bluetooth, cerca de ti.");

        LinearLayout nearby = ui.card("01 · Dispositivos cercanos");
        ui.add(nearby,ui.text("Enciende el accesorio y activa su modo de emparejamiento. Los ya emparejados aparecen aunque estén apagados.",14,DeviceUi.MUTED),12);
        search = ui.button("Buscar Bluetooth",true,this::search); ui.add(nearby,search,12);
        discoveryStatus = ui.text("",14,DeviceUi.MUTED); discoveryStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); ui.add(nearby,discoveryStatus,10);
        deviceList = ui.column(); ui.add(nearby,deviceList,10);
        ui.add(nearby,ui.button("Conectar audio en ajustes",false,this::connectionSettings),10);
        ui.add(nearby,ui.button("Permisos de la app",false,() -> openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:"+getPackageName())))),4);
        if (Build.VERSION.SDK_INT <= 30)
            ui.add(nearby,ui.button("Ajustes de ubicación",false,() -> openSettings(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))),4);

        LinearLayout outputs = ui.card("02 · Salida de audio");
        ui.add(outputs,ui.text("Elige una salida conectada para audio multimedia. Emparejar y conectar el audio son pasos distintos.",14,DeviceUi.MUTED),12);
        outputList = ui.column(); ui.add(outputs,outputList,12);
        outputName = ui.text("",14,DeviceUi.ACCENT); ui.add(outputs,outputName,10);
        if (Build.VERSION.SDK_INT < 28) ui.add(outputs,ui.text("Android 8 elige la ruta de audio en sus ajustes. Esta versión no permite comprobarla desde el reproductor.",13,DeviceUi.MUTED),10);

        LinearLayout content = ui.card("03 · Tu sonido");
        fileName = ui.text("",16,DeviceUi.TEXT); ui.add(content,fileName,14);
        pick = ui.button("Elegir archivo de audio",true,() -> picker.launch(new String[]{"audio/*"})); ui.add(content,pick,12);
        cancelImport = ui.button("Cancelar importación",false,audio::cancelImport); ui.add(content,cancelImport,4);
        ui.add(content,ui.text("MP3, M4A, WAV, Ogg o FLAC, según el soporte del móvil. Hasta 512 MiB. Usa los botones de volumen del móvil para ajustar el sonido.",13,DeviceUi.MUTED),8);
        playbackStatus = ui.text("",14,DeviceUi.MUTED); playbackStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); ui.add(content,playbackStatus,16);
        position = new SeekBar(this); position.setMax(1000); position.setContentDescription("Posición del audio");
        position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar b) { seeking = true; }
            @Override public void onProgressChanged(SeekBar b,int progress,boolean user) {
                if (user) time.setText(format(audio.duration()*progress/1000) + " / " + format(audio.duration()));
            }
            @Override public void onStopTrackingTouch(SeekBar b) { audio.seek(audio.duration()*b.getProgress()/1000); seeking = false; }
        }); ui.add(content,position,12);
        time = ui.text("0:00 / 0:00",13,DeviceUi.MUTED); ui.add(content,time,4);
        repeat = new CheckBox(this); repeat.setText("Repetir audio"); repeat.setTextColor(DeviceUi.TEXT); repeat.setMinHeight(ui.dp(48));
        repeat.setButtonTintList(android.content.res.ColorStateList.valueOf(DeviceUi.ACCENT));
        repeat.setOnCheckedChangeListener((v,checked) -> { if (!rendering) audio.setLoop(checked); }); ui.add(content,repeat,8);
        play = ui.button("Reproducir",true,() -> {
            bluetooth.stop("Búsqueda detenida para reproducir audio."); audio.play();
        }); ui.add(content,play,10);
        pause = ui.button("Pausar",false,() -> audio.pause("Audio en pausa.")); ui.add(content,pause,4);
        stop = ui.button("Detener",false,audio::stop); ui.add(content,stop,4);
        ui.add(ui.body,ui.button("Compatibilidad Bluetooth",false,this::help),16);
    }
    @Override protected void onStart() {
        super.onStart(); bluetooth.addListener(discoveryListener); audio.addListener(audioListener);
        bluetooth.observe(); audio.refreshOutputs(); main.post(clock);
    }
    @Override protected void onStop() {
        bluetooth.removeListener(discoveryListener); audio.removeListener(audioListener);
        bluetooth.leave(); main.removeCallbacks(clock); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("scanAfterEnable",scanAfterEnable); super.onSaveInstanceState(out);
    }
    private void search() {
        if (bluetooth.scanning) { bluetooth.stop("Búsqueda detenida."); return; }
        if (audio.playing || audio.preparing) { bluetooth.message("Pausa el audio antes de buscar para evitar cortes."); return; }
        if (!bluetooth.available()) { bluetooth.message("Este móvil no tiene Bluetooth."); return; }
        if (!bluetooth.permitted()) { permissions.launch(BluetoothDiscovery.permissions()); return; }
        if (!bluetooth.enabled()) {
            scanAfterEnable = true;
            try { enable.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); }
            catch (RuntimeException e) { scanAfterEnable = false; connectionSettings(); }
            return;
        }
        bluetooth.start();
    }
    private void connectionSettings() {
        bluetooth.stop("Conecta el accesorio en los ajustes de Bluetooth y vuelve aquí para elegir la salida.");
        openSettings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }
    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) { bluetooth.message("No se pudieron abrir estos ajustes. Ábrelos desde Ajustes del móvil."); }
    }
    private void showDevice(BluetoothDiscovery.Device d) {
        boolean paired = d.bond == BluetoothDevice.BOND_BONDED;
        String text = d.detail() + "\n\n" + (paired ? "Para escuchar, conecta el audio multimedia en Ajustes. Después vuelve y elige la salida."
                : "Android te pedirá confirmar el emparejamiento si este accesorio lo admite.")
                + (!d.audioHint ? "\nDetectar este dispositivo no significa que acepte audio. Los otros controles IoT dependen de su fabricante." : "");
        new AlertDialog.Builder(this).setTitle(d.name).setMessage(text)
                .setPositiveButton(paired ? "Conectar audio" : "Emparejar",(dialog,which) -> {
                    if (paired) connectionSettings(); else bluetooth.pair(d);
                }).setNegativeButton("Cerrar",null).show();
    }
    private void renderDevices() {
        if (ui == null) return;
        search.setEnabled(bluetooth.available() && !audio.playing && !audio.preparing);
        search.setText(bluetooth.scanning ? "Detener búsqueda" : "Buscar Bluetooth");
        discoveryStatus.setText(bluetooth.status);
        StringBuilder signature = new StringBuilder();
        for (BluetoothDiscovery.Device d : bluetooth.devices()) signature.append(d.address).append(d.name).append(d.bond).append(d.classic).append(d.ble).append(d.audioHint);
        // Do not rebuild focused/touchable rows for every RSSI advertisement.
        String key = signature.toString(); if (key.equals(deviceSignature) && deviceList.getChildCount() > 0) return;
        deviceSignature = key; deviceList.removeAllViews();
        if (bluetooth.devices().isEmpty()) ui.add(deviceList,ui.text("Los dispositivos aparecerán aquí. Un accesorio conectado a otro móvil puede no anunciarse.",14,DeviceUi.MUTED),4);
        for (BluetoothDiscovery.Device d : bluetooth.devices()) {
            Button b = ui.button(d.name + "\n" + d.detail(),false,() -> showDevice(d));
            b.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); ui.add(deviceList,b,6);
        }
    }
    private void renderAudio() {
        if (ui == null) return;
        rendering = true;
        fileName.setText(audio.file == null ? "Elige el audio que quieres escuchar." : audio.file.name + "\n" + String.format(Locale.getDefault(),"%.1f MiB",audio.file.file.length()/1048576.0));
        playbackStatus.setText(audio.status); outputName.setText(audio.selectedName);
        pick.setEnabled(!audio.importing); cancelImport.setVisibility(audio.importing ? View.VISIBLE : View.GONE);
        play.setEnabled(audio.file != null && audio.selectedId != -1 && !audio.importing && !audio.playing && !audio.preparing);
        play.setText(audio.preparing ? "Preparando…" : audio.playing ? "Reproduciendo" : audio.position() > 0 ? "Reanudar" : "Reproducir");
        pause.setEnabled(audio.playing || audio.preparing); stop.setEnabled(audio.playing || audio.preparing || audio.canSeek());
        repeat.setChecked(audio.loop); rendering = false;
        StringBuilder signature = new StringBuilder().append(audio.selectedId);
        for (AudioDeviceInfo d : audio.outputs) signature.append('|').append(d.getId()).append(AudioController.outputLabel(d));
        String key = signature.toString();
        if (!key.equals(outputSignature)) {
            outputSignature = key; outputList.removeAllViews();
            if (audio.outputs.isEmpty()) ui.add(outputList,ui.text("Todavía no hay una salida de audio Bluetooth conectada. Pulsa Conectar audio en ajustes.",14,DeviceUi.MUTED),0);
            for (AudioDeviceInfo d : audio.outputs) ui.add(outputList,ui.button((d.getId() == audio.selectedId ? "✓ " : "")
                    + AudioController.outputLabel(d),d.getId() == audio.selectedId,() -> audio.selectOutput(d.getId())),6);
        }
        renderPosition(); renderDevices();
    }
    private void renderPosition() {
        if (position == null || seeking) return;
        long duration = audio.duration(), value = audio.position();
        position.setEnabled(audio.canSeek() && duration > 0);
        position.setProgress(duration <= 0 ? 0 : (int)Math.min(1000,value*1000/duration));
        time.setText(format(value) + " / " + format(duration));
    }
    private static String format(long ms) {
        long seconds = Math.max(0,ms/1000); return String.format(Locale.getDefault(),"%d:%02d",seconds/60,seconds%60);
    }
    private void help() {
        new AlertDialog.Builder(this).setTitle("Conectar tu sonido").setMessage(
                "1. Busca con el accesorio en modo de emparejamiento.\n2. Pulsa el dispositivo y acepta la solicitud de Android.\n"
                + "3. Si hace falta, abre Conectar audio en ajustes y activa Audio multimedia.\n4. Vuelve aquí, elige la salida, selecciona un archivo y pulsa Reproducir.\n\n"
                + "A2DP sirve para la mayoría de altavoces y auriculares. LE Audio necesita un móvil, sistema y accesorio compatibles; Android negocia el códec. "
                + "Detectar BLE no confirma LE Audio. Esta versión no inicia emisiones Auracast ni reproduce en varios accesorios por su cuenta.\n\n"
                + "Android puede requerir que completes la conexión en sus ajustes. Los dispositivos que solo ofrecen audio de llamadas no aparecen como salida multimedia.")
                .setPositiveButton("Entendido",null).show();
    }
}
