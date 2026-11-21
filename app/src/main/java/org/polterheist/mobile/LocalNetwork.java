package org.polterheist.mobile;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;

public final class LocalNetwork {
    public final Network network;
    public final Inet4Address address;
    public final int prefix;
    public final String label;

    private LocalNetwork(Network network, Inet4Address address, int prefix, String label) {
        this.network = network; this.address = address; this.prefix = prefix; this.label = label;
    }

    public static LocalNetwork find(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        for (int transport : new int[]{NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET}) {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                LinkProperties properties = manager.getLinkProperties(network);
                if (caps == null || properties == null || !caps.hasTransport(transport) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLoopbackAddress()) {
                        return new LocalNetwork(network, (Inet4Address) link.getAddress(), link.getPrefixLength(),
                                transport == NetworkCapabilities.TRANSPORT_WIFI ? "Wi-Fi" : "Ethernet");
                    }
                }
            }
        }
        throw new IOException("Conecta el móvil al Wi-Fi de tus televisores. Hace falta una dirección IPv4 local.");
    }

    public boolean contains(InetAddress other) {
        if (!(other instanceof Inet4Address) || other.isAnyLocalAddress() || other.isMulticastAddress() || other.isLoopbackAddress()) return false;
        byte[] a = address.getAddress(), b = other.getAddress();
        for (int bit = 0; bit < prefix; bit++) if ((a[bit / 8] & (1 << (7 - bit % 8))) != (b[bit / 8] & (1 << (7 - bit % 8)))) return false;
        return true;
    }

    public HttpURLConnection open(URL url) throws IOException {
        return (HttpURLConnection) network.openConnection(url, java.net.Proxy.NO_PROXY);
    }
}
