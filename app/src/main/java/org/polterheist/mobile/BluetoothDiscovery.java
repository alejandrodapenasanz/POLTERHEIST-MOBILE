package org.polterheist.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.location.LocationManager;
import android.os.*;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import java.util.*;

/** Foreground, bounded Classic inquiry followed by BLE advertising discovery. No GATT audio fiction. */
@SuppressLint("MissingPermission") // Permission gates below; revocation is also caught at API boundaries.
public final class BluetoothDiscovery {
    public static final class Device {
        public final BluetoothDevice handle;
        public final String address;
        public String name = "Dispositivo sin nombre";
        public boolean classic, ble, audioHint;
        public int bond, rssi = Integer.MIN_VALUE;
        Device(BluetoothDevice handle) { this.handle = handle; address = handle.getAddress(); }
        public String detail() {
            String radio = classic && ble ? "Classic + BLE" : ble ? "BLE" : "Bluetooth";
            String state = bond == BluetoothDevice.BOND_BONDED ? "Emparejado" : bond == BluetoothDevice.BOND_BONDING ? "Emparejando…" : "Sin emparejar";
            return state + " · " + radio + (audioHint ? " · posible audio" : "") + "\n" + address
                    + (rssi == Integer.MIN_VALUE ? "" : " · " + rssi + " dBm");
        }
    }
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> found = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean registered, classicStarted;
    private int generation;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private final Runnable blePhase = this::startBle;
    private final Runnable timeout = () -> stop("Búsqueda terminada. Si falta un accesorio, ponlo en modo de emparejamiento y vuelve a buscar.");
    public boolean scanning;
    public String status = "Pon tu accesorio en modo de emparejamiento y pulsa Buscar.";

    public BluetoothDiscovery(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public static String[] permissions() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    public boolean permitted() { return NearbyPermissions.bluetoothScan(context) && NearbyPermissions.bluetoothConnect(context); }
    public boolean available() { return adapter != null; }
    public boolean enabled() {
        try { return adapter != null && NearbyPermissions.bluetoothConnect(context) && adapter.isEnabled(); }
        catch (SecurityException e) { return false; }
    }
    public void addListener(Runnable r) { listeners.add(r); r.run(); }
    public void removeListener(Runnable r) { listeners.remove(r); }
    private void changed() { for (Runnable r : new ArrayList<>(listeners)) r.run(); }
    public void message(String text) { status = text; changed(); }
    public List<Device> devices() {
        List<Device> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparing((Device d) -> d.bond != BluetoothDevice.BOND_BONDED)
                .thenComparing(d -> !d.audioHint).thenComparing(d -> d.name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }
    public void observe() {
        if (adapter == null) { message("Este móvil no tiene Bluetooth."); return; }
        if (!permitted()) { message("Permite el acceso a dispositivos cercanos para buscar y emparejar."); return; }
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            // All actions are protected system broadcasts; Bluetooth has a separate privileged UID.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
            registered = true;
        }
        refreshBonded();
        if (!enabled()) message("Bluetooth apagado. Actívalo para conectar tus accesorios.");
    }
    public void leave() {
        stop(scanning ? "Búsqueda detenida al salir de la pantalla." : status);
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
    }
    private void refreshBonded() {
        if (!NearbyPermissions.bluetoothConnect(context) || adapter == null) return;
        try {
            for (Device d : found.values()) d.bond = d.handle.getBondState();
            for (BluetoothDevice d : adapter.getBondedDevices()) record(d,null,null,Integer.MIN_VALUE,false);
            changed();
        } catch (SecurityException e) { message("El permiso de Bluetooth ya no está disponible."); }
    }
    public void start() {
        stop(status); observe();
        if (!permitted() || !enabled()) return;
        if (Build.VERSION.SDK_INT <= 30 && !locationEnabled()) {
            message("En esta versión de Android, activa también Ubicación en Ajustes para buscar Bluetooth."); return;
        }
        found.clear(); refreshBonded(); scanning = true;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            classicStarted = adapter.startDiscovery();
            if (classicStarted) {
                message("Buscando Bluetooth Classic… Después buscaré accesorios BLE.");
                main.postDelayed(blePhase, 13_000);
            } else startBle();
        } catch (SecurityException | IllegalStateException e) { stop("No se pudo iniciar la búsqueda. Revisa Bluetooth y sus permisos."); }
    }
    private boolean locationEnabled() {
        LocationManager m = context.getSystemService(LocationManager.class);
        if (m == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return m.isLocationEnabled();
        return Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.LOCATION_MODE,0) != 0;
    }
    private void startBle() {
        if (!scanning) return;
        if (!enabled()) { stop("Bluetooth ya no está disponible. Revisa los permisos y vuelve a buscar."); return; }
        try {
            if (classicStarted) { adapter.cancelDiscovery(); classicStarted = false; }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { stop("Búsqueda Classic terminada. BLE no está disponible."); return; }
            int scan = generation;
            callback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    main.post(() -> {
                        if (!scanning || scan != generation) return;
                        ScanRecord r = result.getScanRecord();
                        record(result.getDevice(), r == null ? null : r.getDeviceName(), r, result.getRssi(),true);
                    });
                }
                @Override public void onScanFailed(int error) {
                    main.post(() -> { if (scanning && scan == generation) stop("Android no pudo buscar BLE (" + error + "). Espera unos segundos antes de reintentar."); });
                }
            };
            scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback);
            main.postDelayed(timeout,8_000);
            message("Buscando Bluetooth LE… Los dispositivos detectados no siempre ofrecen audio.");
        } catch (SecurityException | IllegalStateException e) { stop("No se pudo buscar BLE. Revisa Bluetooth y sus permisos."); }
    }
    public void stop(String note) {
        generation++; scanning = false; main.removeCallbacks(blePhase); main.removeCallbacks(timeout);
        try { if (classicStarted && adapter != null) adapter.cancelDiscovery(); }
        catch (SecurityException | IllegalStateException ignored) { }
        try { if (scanner != null && callback != null) scanner.stopScan(callback); }
        catch (SecurityException | IllegalStateException ignored) { }
        classicStarted = false; scanner = null; callback = null; status = note; changed();
    }
    public void pair(Device device) {
        stop(status);
        if (!NearbyPermissions.bluetoothConnect(context)) { message("Falta el permiso de Bluetooth."); return; }
        try {
            if (device.handle.getBondState() == BluetoothDevice.BOND_BONDED) {
                message("Ya está emparejado. Conecta el audio multimedia desde Ajustes de Bluetooth.");
            } else if (device.handle.createBond()) {
                device.bond = BluetoothDevice.BOND_BONDING;
                message("Emparejando con " + device.name + ". Confirma la solicitud de Android si aparece.");
            } else message("No se pudo iniciar el emparejamiento. Pon el accesorio en modo de emparejamiento y reintenta.");
        } catch (SecurityException | IllegalStateException e) { message("Android no permitió emparejar. Revisa sus ajustes de Bluetooth."); }
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                if (!enabled()) { stop("Bluetooth apagado."); found.clear(); changed(); }
                else { refreshBonded(); message("Bluetooth activado. Ya puedes buscar."); }
                return;
            }
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                if (scanning) record(d,intent.getStringExtra(BluetoothDevice.EXTRA_NAME),null,
                        intent.getShortExtra(BluetoothDevice.EXTRA_RSSI,Short.MIN_VALUE),false);
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                record(d,null,null,Integer.MIN_VALUE,false);
                int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE);
                message(bond == BluetoothDevice.BOND_BONDED
                        ? "Emparejado. Si no aparece una salida de audio, pulsa Conectar audio en ajustes."
                        : bond == BluetoothDevice.BOND_BONDING ? "Emparejando… Confirma la solicitud de Android."
                        : "Emparejamiento cancelado o eliminado. Puedes volver a intentarlo.");
            }
        }
    };
    private void record(BluetoothDevice handle, String advertisedName, ScanRecord advertisement, int rssi, boolean ble) {
        if (!NearbyPermissions.bluetoothConnect(context)) return;
        try {
            String address = handle.getAddress();
            Device d = found.get(address);
            if (d == null) {
                if (found.size() >= 128) return;
                d = new Device(handle); found.put(address,d);
            }
            String name = advertisedName == null ? handle.getName() : advertisedName;
            if (name != null && !name.trim().isEmpty()) d.name = name.trim();
            d.bond = handle.getBondState();
            int type = handle.getType();
            d.classic |= type == BluetoothDevice.DEVICE_TYPE_CLASSIC || type == BluetoothDevice.DEVICE_TYPE_DUAL;
            d.ble |= ble || type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL;
            if (rssi != Integer.MIN_VALUE && rssi != Short.MIN_VALUE) d.rssi = rssi;
            BluetoothClass cl = handle.getBluetoothClass();
            d.audioHint |= cl != null && cl.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
            ParcelUuid[] uuids = handle.getUuids();
            if (uuids != null) for (ParcelUuid uuid : uuids) d.audioHint |= audioUuid(uuid);
            if (advertisement != null && advertisement.getServiceUuids() != null)
                for (ParcelUuid uuid : advertisement.getServiceUuids()) d.audioHint |= audioUuid(uuid);
            changed();
        } catch (SecurityException ignored) { }
    }
    private static boolean audioUuid(ParcelUuid uuid) {
        String s = uuid.toString();
        return s.equals("0000110b-0000-1000-8000-00805f9b34fb") // A2DP sink
                || s.equals("0000184e-0000-1000-8000-00805f9b34fb") // LE Audio Stream Control
                || s.equals("00001850-0000-1000-8000-00805f9b34fb"); // Published Audio Capabilities
    }
}
