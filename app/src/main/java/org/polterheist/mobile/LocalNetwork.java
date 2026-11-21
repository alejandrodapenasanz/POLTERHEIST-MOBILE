package org.polterheist.mobile;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * A local IPv4 path between the phone and a TV.
 *
 * Infrastructure networks (Wi-Fi, Ethernet) come from ConnectivityManager and carry a {@link Network}.
 * Wi-Fi Direct groups and the phone's own hotspot are not exposed as Networks to apps, so they are found by
 * enumerating {@link NetworkInterface}s; for those {@link #network} is null and sockets are bound by local address.
 */
public final class LocalNetwork {
    public enum Kind { WIFI, ETHERNET, WIFI_DIRECT, HOTSPOT, OTHER }
    /** Null for Wi-Fi Direct / hotspot / other raw interfaces. */
    public final Network network;
    public final Inet4Address address;
    public final int prefix;
    public final String label;
    public final Kind kind;
    public final String interfaceName;

    private LocalNetwork(Network network, Inet4Address address, int prefix, String label, Kind kind, String interfaceName) {
        this.network = network; this.address = address; this.prefix = prefix; this.label = label;
        this.kind = kind; this.interfaceName = interfaceName == null ? "" : interfaceName;
    }

    /** Primary infrastructure network (Wi-Fi first, then Ethernet). Used by Cast and as default route to other subnets. */
    public static LocalNetwork find(Context context) throws IOException {
        List<LocalNetwork> infra = infrastructure(context);
        if (!infra.isEmpty()) return infra.get(0);
        throw new IOException("Conecta el móvil al Wi-Fi de tus televisores. Hace falta una dirección IPv4 local.");
    }

    /** Every usable local path: Wi-Fi/Ethernet plus Wi-Fi Direct groups and the phone's hotspot. Never throws. */
    public static List<LocalNetwork> findAll(Context context) {
        List<LocalNetwork> result = new ArrayList<>(infrastructure(context));
        Set<InetAddress> known = new HashSet<>();
        for (LocalNetwork n : result) known.add(n.address);
        // JVM unit tests (Robolectric) would otherwise enumerate the build machine's real interfaces.
        if ("robolectric".equals(android.os.Build.FINGERPRINT)) return result;
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface nic = all.nextElement();
                try { if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) continue; }
                catch (SocketException e) { continue; }
                Kind kind = kindOf(nic.getName());
                if (kind == null) continue;
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    Inet4Address address = (Inet4Address) ia.getAddress();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress() || !known.add(address)) continue;
                    result.add(new LocalNetwork(null, address, ia.getNetworkPrefixLength(), labelOf(kind), kind, nic.getName()));
                }
            }
        } catch (SocketException ignored) { }
        return result;
    }

    /** Network whose subnet contains the peer; otherwise the primary network (peer reachable through the router). */
    public static LocalNetwork forPeer(Context context, InetAddress peer) throws IOException {
        for (LocalNetwork n : findAll(context)) if (n.contains(peer)) return n;
        return find(context);
    }
    public static LocalNetwork forPeer(Context context, String host) throws IOException {
        return forPeer(context, InetAddress.getByName(host));
    }

    /** First network of the given kind, or null. */
    public static LocalNetwork ofKind(Context context, Kind kind) {
        for (LocalNetwork n : findAll(context)) if (n.kind == kind) return n;
        return null;
    }

    private static List<LocalNetwork> infrastructure(Context context) {
        List<LocalNetwork> result = new ArrayList<>();
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return result;
        for (int transport : new int[]{NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET}) {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                LinkProperties properties = manager.getLinkProperties(network);
                if (caps == null || properties == null || !caps.hasTransport(transport) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress()) {
                        boolean wifi = transport == NetworkCapabilities.TRANSPORT_WIFI;
                        result.add(new LocalNetwork(network, (Inet4Address) link.getAddress(), link.getPrefixLength(),
                                wifi ? "Wi-Fi" : "Ethernet", wifi ? Kind.WIFI : Kind.ETHERNET, properties.getInterfaceName()));
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** Interface name heuristics. Mobile data, VPN and USB tethering interfaces are ignored (null). */
    static Kind kindOf(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("p2p")) return Kind.WIFI_DIRECT;
        if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.equals("wlan1") || n.equals("wlan2")) return Kind.HOTSPOT;
        if (n.startsWith("wlan")) return Kind.WIFI;
        if (n.startsWith("eth")) return Kind.ETHERNET;
        return null;
    }
    private static String labelOf(Kind kind) {
        switch (kind) {
            case WIFI_DIRECT: return "Wi-Fi Direct";
            case HOTSPOT: return "Red del móvil";
            case ETHERNET: return "Ethernet";
            case WIFI: return "Wi-Fi";
            default: return "Red local";
        }
    }

    public boolean contains(InetAddress other) {
        if (!(other instanceof Inet4Address) || other.isAnyLocalAddress() || other.isMulticastAddress() || other.isLoopbackAddress()) return false;
        byte[] a = address.getAddress(), b = other.getAddress();
        for (int bit = 0; bit < prefix; bit++) if ((a[bit / 8] & (1 << (7 - bit % 8))) != (b[bit / 8] & (1 << (7 - bit % 8)))) return false;
        return true;
    }

    public HttpURLConnection open(URL url) throws IOException {
        if (network != null) return (HttpURLConnection) network.openConnection(url, java.net.Proxy.NO_PROXY);
        return (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
    }

    /** Binds a datagram socket to this path (Network when available, local address always). */
    public void bind(DatagramSocket socket) throws IOException {
        if (network != null) network.bindSocket(socket);
        socket.bind(new InetSocketAddress(address, 0));
    }

    public boolean sameAs(LocalNetwork other) { return other != null && address.equals(other.address); }
}
