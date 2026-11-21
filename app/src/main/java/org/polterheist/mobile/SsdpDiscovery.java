package org.polterheist.mobile;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * SSDP/UPnP discovery.
 * <ul>
 *   <li>Multicast M-SEARCH on one local path (Wi-Fi, Ethernet, Wi-Fi Direct group, phone hotspot).</li>
 *   <li>Unicast M-SEARCH to one IP or to a user-entered range, also on other (routed) subnets.</li>
 *   <li>For single IPs, direct HTTP probing of well-known description URLs, for TVs that ignore unicast SSDP.</li>
 * </ul>
 */
public final class SsdpDiscovery {
    /** Best-effort well-known UPnP description locations ("http://IP" + suffix). Unverified per model. */
    static final String[] KNOWN_DESCRIPTIONS = {
            ":9197/dmr",                 // Samsung (DMR)
            ":52323/dmr.xml",            // Sony Bravia
            ":49152/description.xml",    // Generic UPnP stacks (libupnp) - several TVs/boxes
            ":49153/description.xml",
            ":49154/description.xml",
            ":1400/xml/device_description.xml" // Sonos-like renderers (some TV soundbars)
    };
    private static final int MAX_SWEEP = 1024;

    public static void scan(Context context, LocalNetwork network, String target,
                            Consumer<DlnaDevice> found, Consumer<String> warning) throws Exception {
        scan(context, network, target == null ? null : List.of(InetAddress.getByName(target)), found, warning);
    }

    /** @param targets null for multicast on {@code network}; otherwise unicast to each address (any subnet). */
    public static void scan(Context context, LocalNetwork network, List<? extends InetAddress> targets,
                            Consumer<DlnaDevice> found, Consumer<String> warning) throws Exception {
        if (targets != null && targets.size() > MAX_SWEEP) throw new IllegalArgumentException("Rango demasiado grande (máx. " + MAX_SWEEP + " direcciones).");
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("polterheist-discovery");
        if (lock != null) lock.setReferenceCounted(false);
        ExecutorService descriptions = Executors.newFixedThreadPool(4);
        List<Future<?>> work = Collections.synchronizedList(new ArrayList<>());
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        Set<InetAddress> accepted = targets == null ? null : new HashSet<>(targets);
        DlnaClient client = new DlnaClient(network::open);
        try (MulticastSocket socket = new MulticastSocket(null)) {
            if (lock != null) lock.acquire();
            network.bind(socket);
            NetworkInterface nic = NetworkInterface.getByInetAddress(network.address);
            if (nic != null) socket.setNetworkInterface(nic);
            socket.setTimeToLive(2);
            socket.setSoTimeout(500);

            // TVs that ignore SSDP: probe known description URLs directly (single target only, cheap).
            if (targets != null && targets.size() == 1) {
                String peer = targets.get(0).getHostAddress();
                String via = viaLabel(network, targets.get(0));
                for (String suffix : KNOWN_DESCRIPTIONS) {
                    String location = "http://" + peer + suffix;
                    if (!seen.add(location)) continue;
                    work.add(descriptions.submit(() -> {
                        try { for (DlnaDevice d : client.describe(new URL(location), peer)) found.accept(d.withVia(via)); }
                        catch (Exception ignored) { /* port closed or not a renderer */ }
                    }));
                }
            }

            String message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
            byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
            List<InetAddress> destinations = targets == null ? List.of(InetAddress.getByName("239.255.255.250")) : new ArrayList<>(targets);
            sendAll(socket, bytes, destinations);
            int sends = 1, maxSends = targets == null ? Integer.MAX_VALUE : 3;
            long listenMs = 6500 + (targets == null ? 0 : Math.min(6000, targets.size() * 4L));
            int cap = targets == null ? 32 : 64;
            long start = System.nanoTime(), lastSend = start;
            while ((System.nanoTime() - start) / 1_000_000 < listenMs && seen.size() < cap + KNOWN_DESCRIPTIONS.length && !Thread.currentThread().isInterrupted()) {
                if (sends < maxSends && (System.nanoTime() - lastSend) / 1_000_000 > 2200) { sendAll(socket, bytes, destinations); sends++; lastSend = System.nanoTime(); }
                DatagramPacket reply = new DatagramPacket(new byte[16384], 16384);
                try { socket.receive(reply); } catch (SocketTimeoutException e) { continue; }
                InetAddress from = reply.getAddress();
                if (accepted == null ? !network.contains(from) : !accepted.contains(from)) continue;
                String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.ISO_8859_1);
                if (!text.startsWith("HTTP/1.1 200")) continue;
                for (String line : text.split("\r\n")) {
                    int colon = line.indexOf(':');
                    if (colon < 0 || !line.substring(0, colon).equalsIgnoreCase("location")) continue;
                    String location = line.substring(colon + 1).trim(), peer = from.getHostAddress();
                    if (!seen.add(location)) continue;
                    String via = viaLabel(network, from);
                    work.add(descriptions.submit(() -> {
                        try {
                            URL url = UpnpXml.samePeer(new URL(location), peer);
                            for (DlnaDevice device : client.describe(url, peer)) found.accept(device.withVia(via));
                        } catch (Exception e) { warning.accept("Un dispositivo anunció DLNA pero no se pudo verificar: " + peer); }
                    }));
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
            for (Future<?> future : new ArrayList<>(work)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                try { future.get(left, TimeUnit.NANOSECONDS); } catch (ExecutionException | TimeoutException ignored) { }
            }
        } finally {
            descriptions.shutdownNow();
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    private static void sendAll(DatagramSocket socket, byte[] bytes, List<InetAddress> destinations) throws Exception {
        int i = 0;
        for (InetAddress destination : destinations) {
            try { socket.send(new DatagramPacket(bytes, bytes.length, destination, 1900)); }
            catch (java.io.IOException e) { if (destinations.size() == 1) throw e; /* unreachable host in a sweep */ }
            if (++i % 64 == 0) Thread.sleep(25); // keep sweeps gentle on routers
        }
    }

    static String viaLabel(LocalNetwork network, InetAddress peer) {
        return network.contains(peer) ? network.label : "otra red";
    }
}
