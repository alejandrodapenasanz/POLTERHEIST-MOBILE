package org.polterheist.mobile;

/**
 * A TV-like device detected by a method that cannot play media by itself
 * (Bluetooth, Wi-Fi Direct peer, mDNS service without DLNA). Selecting it starts a bridge to a playable path.
 */
public final class NearbyDevice {
    public enum Source { BLUETOOTH, WIFI_DIRECT, MDNS }
    public final Source source;
    /** Stable key: MAC for Bluetooth / Wi-Fi Direct, "ip|serviceType" for mDNS. */
    public final String key;
    public final String name;
    public final String detail;
    /** IPv4 for mDNS; MAC for Bluetooth / Wi-Fi Direct. */
    public final String address;
    /** android.bluetooth.BluetoothDevice or android.net.wifi.p2p.WifiP2pDevice, depending on source. */
    public final Object handle;

    public NearbyDevice(Source source, String key, String name, String detail, String address, Object handle) {
        this.source = source; this.key = key; this.name = name == null || name.isBlank() ? "TV sin nombre" : name.trim();
        this.detail = detail == null ? "" : detail; this.address = address == null ? "" : address; this.handle = handle;
    }

    public String sourceLabel() {
        switch (source) {
            case BLUETOOTH: return "Bluetooth";
            case WIFI_DIRECT: return "Wi-Fi Direct";
            default: return "mDNS";
        }
    }

    /** Lowercase alphanumeric name without decorations like "[TV]", for matching the same TV across radios. */
    public static String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\(.*?\\)", "")
                .replaceAll("[^a-z0-9]", "");
    }
    /** Loose name match (one contains the other, at least 4 chars). */
    public static boolean sameTv(String a, String b) {
        String x = normalize(a), y = normalize(b);
        if (x.length() < 4 || y.length() < 4) return false;
        return x.contains(y) || y.contains(x);
    }
}
