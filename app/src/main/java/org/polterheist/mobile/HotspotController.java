package org.polterheist.mobile;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Phone-owned Wi-Fi network (LocalOnlyHotspot) for TVs that are on another network or on none.
 * The user joins the TV to the shown SSID; discovery and the media server then run on the hotspot interface.
 * No Internet is shared. Some phones turn regular Wi-Fi off while the hotspot is active.
 */
@SuppressLint("MissingPermission")
public final class HotspotController {
    public interface Listener { void started(String ssid, String password); void stopped(String reason); }

    private final Context context;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    public String ssid = "", password = "";

    public HotspotController(Context context) { this.context = context.getApplicationContext(); }
    public boolean active() { return reservation != null; }

    public void start(Listener listener) {
        if (reservation != null) { listener.started(ssid, password); return; }
        if (!NearbyPermissions.wifi(context)) { listener.stopped("Falta el permiso de dispositivos Wi-Fi cercanos / ubicación."); return; }
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi == null) { listener.stopped("Este móvil no permite crear una red Wi-Fi."); return; }
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    reservation = r;
                    readCredentials(r);
                    listener.started(ssid, password);
                }
                @Override public void onStopped() { reservation = null; listener.stopped("Android cerró la red del móvil."); }
                @Override public void onFailed(int reason) {
                    reservation = null;
                    listener.stopped(reason == ERROR_INCOMPATIBLE_MODE ? "No se puede crear la red: desactiva el punto de acceso normal o el anclaje de red."
                            : reason == ERROR_TETHERING_DISALLOWED ? "El administrador o el operador no permiten crear redes en este móvil."
                            : "No se pudo crear la red del móvil (" + reason + ").");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            listener.stopped("Android exige activar la ubicación del móvil para crear la red.");
        } catch (IllegalStateException e) {
            listener.stopped("Ya hay una red del móvil activa en otra app.");
        }
    }

    public void stop() {
        if (reservation != null) { reservation.close(); reservation = null; }
        ssid = ""; password = "";
    }

    @SuppressWarnings("deprecation")
    private void readCredentials(WifiManager.LocalOnlyHotspotReservation r) {
        if (Build.VERSION.SDK_INT >= 30 && r.getSoftApConfiguration() != null) {
            ssid = r.getSoftApConfiguration().getSsid();
            password = r.getSoftApConfiguration().getPassphrase();
        } else if (r.getWifiConfiguration() != null) {
            ssid = r.getWifiConfiguration().SSID;
            password = r.getWifiConfiguration().preSharedKey;
        }
        if (ssid == null) ssid = ""; else ssid = ssid.replace("\"", "");
        if (password == null) password = ""; else password = password.replace("\"", "");
    }
}
