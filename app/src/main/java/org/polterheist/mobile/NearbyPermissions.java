package org.polterheist.mobile;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

/** Runtime permissions for discovery outside the local network (Wi-Fi Direct, Bluetooth, phone hotspot). */
public final class NearbyPermissions {
    private NearbyPermissions() { }

    /** Everything the "search nearby" flow may ask for on this Android version. */
    public static String[] all() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        else p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) { p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT); }
        return p.toArray(new String[0]);
    }
    public static String[] missing(Context context) {
        List<String> p = new ArrayList<>();
        for (String permission : all()) if (!granted(context, permission)) p.add(permission);
        return p.toArray(new String[0]);
    }

    /** Wi-Fi Direct discovery/connect and LocalOnlyHotspot. */
    public static boolean wifi(Context context) {
        return Build.VERSION.SDK_INT >= 33 ? granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                : granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }
    public static boolean bluetoothScan(Context context) {
        return Build.VERSION.SDK_INT >= 31 ? granted(context, Manifest.permission.BLUETOOTH_SCAN)
                : granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }
    /** Needed (API 31+) to read names of found devices and to pair. */
    public static boolean bluetoothConnect(Context context) {
        return Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT);
    }
    private static boolean granted(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }
}
