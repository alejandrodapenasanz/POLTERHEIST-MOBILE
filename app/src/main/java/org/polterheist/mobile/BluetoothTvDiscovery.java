package org.polterheist.mobile;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import androidx.core.content.ContextCompat;
import java.util.*;

/**
 * Detects TVs over Bluetooth Classic inquiry and BLE advertising. Bluetooth cannot carry the video;
 * a detected TV is a starting point to bridge to Wi-Fi Direct (same TV name) or other paths.
 *
 * Every call is guarded by {@link NearbyPermissions}; lint's MissingPermission is suppressed for that reason.
 */
@SuppressLint("MissingPermission")
public final class BluetoothTvDiscovery {
    public interface Listener {
        void found(BluetoothDevice device, String name, String detail);
        void finished(String note);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private Listener listener;
    private boolean registered, running;
    private final Set<String> reported = new HashSet<>();
    private final Runnable timeout = this::stop;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                BluetoothClass type = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
                if (device != null) consider(device, name, type, "Bluetooth");
            }
        }
    };
    private final ScanCallback bleCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            String name = record != null && record.getDeviceName() != null ? record.getDeviceName() : safeName(device);
            String vendor = record == null ? "" : vendor(record.getManufacturerSpecificData());
            main.post(() -> consider(device, name, null, vendor.isEmpty() ? "Bluetooth LE" : "Bluetooth LE · " + vendor));
        }
    };

    public BluetoothTvDiscovery(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean available() { return adapter != null; }
    public boolean running() { return running; }

    public void start(Listener listener, long durationMs) {
        stop();
        this.listener = listener;
        reported.clear();
        if (adapter == null) { listener.finished("Este móvil no tiene Bluetooth."); return; }
        if (!NearbyPermissions.bluetoothScan(context) || !NearbyPermissions.bluetoothConnect(context)) { listener.finished("Falta el permiso de Bluetooth para buscar TVs cercanas."); return; }
        if (!adapter.isEnabled()) { listener.finished("Activa el Bluetooth para detectar TVs cercanas."); return; }
        running = true;
        // Already paired TVs are reported immediately.
        for (BluetoothDevice device : adapter.getBondedDevices()) consider(device, safeName(device), device.getBluetoothClass(), "Bluetooth · emparejada");
        if (!registered) {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            // Bluetooth broadcasts originate from a privileged process with its own UID.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
            registered = true;
        }
        try { adapter.startDiscovery(); } catch (SecurityException ignored) { }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner != null) {
            try { scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
        }
        main.postDelayed(timeout, durationMs);
    }

    public void stop() {
        main.removeCallbacks(timeout);
        if (!running) return;
        running = false;
        try {
            if (adapter != null) {
                adapter.cancelDiscovery();
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner != null) scanner.stopScan(bleCallback);
            }
        } catch (SecurityException | IllegalStateException ignored) { }
        if (registered) { try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { } registered = false; }
        if (listener != null) listener.finished("");
    }

    /** Requests Bluetooth pairing (useful for TV remote/audio features; video still needs Wi-Fi). */
    public boolean pair(BluetoothDevice device) {
        if (!NearbyPermissions.bluetoothConnect(context)) return false;
        try { return device.getBondState() == BluetoothDevice.BOND_BONDED || device.createBond(); }
        catch (SecurityException e) { return false; }
    }

    private void consider(BluetoothDevice device, String name, BluetoothClass type, String detail) {
        if (!running || listener == null || device == null) return;
        boolean tv = classLooksLikeTv(type) || nameLooksLikeTv(name);
        if (!tv || !reported.add(device.getAddress())) return;
        listener.found(device, name == null || name.isBlank() ? "TV Bluetooth" : name, detail);
    }

    private String safeName(BluetoothDevice device) {
        try { return device.getName(); } catch (SecurityException e) { return null; }
    }

    static boolean classLooksLikeTv(BluetoothClass type) {
        if (type == null || type.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) return false;
        int d = type.getDeviceClass();
        return d == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER || d == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR
                || d == BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX || d == BluetoothClass.Device.AUDIO_VIDEO_VCR;
    }

    /** Name heuristics for TVs and TV sticks. Phones ("Galaxy S..") are intentionally not matched. */
    public static boolean nameLooksLikeTv(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.matches(".*(\\[tv]|\\[lg]|\\btv\\b|bravia|webos|tizen|qled|oled|viera|chromecast|fire ?tv|firetv|roku|shield|mi ?box|android ?tv|google ?tv|smart ?tv|hisense|vidaa|philips|tcl|aquos|\\bueq?\\d{2}|\\bqe\\d{2}).*");
    }

    private static String vendor(SparseArray<byte[]> data) {
        if (data == null) return "";
        for (int i = 0; i < data.size(); i++) {
            switch (data.keyAt(i)) {
                case 0x0075: return "Samsung";
                case 0x00C4: return "LG";
                case 0x012D: return "Sony";
                case 0x00E0: return "Google";
                case 0x0171: return "Amazon";
                default: break;
            }
        }
        return "";
    }
}
