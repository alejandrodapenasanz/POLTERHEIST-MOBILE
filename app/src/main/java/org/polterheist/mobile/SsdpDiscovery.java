package org.polterheist.mobile;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class SsdpDiscovery {
    public static void scan(Context context, LocalNetwork network, String target,
                            Consumer<DlnaDevice> found, Consumer<String> warning) throws Exception {
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi.createMulticastLock("polterheist-discovery");
        lock.setReferenceCounted(false);
        ExecutorService descriptions = Executors.newFixedThreadPool(4);
        List<Future<?>> work = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (MulticastSocket socket = new MulticastSocket(null)) {
            lock.acquire();
            network.network.bindSocket(socket);
            socket.bind(new InetSocketAddress(network.address, 0));
            socket.setNetworkInterface(NetworkInterface.getByInetAddress(network.address));
            socket.setTimeToLive(2);
            socket.setSoTimeout(500);
            InetAddress destination = InetAddress.getByName(target == null ? "239.255.255.250" : target);
            if (target != null && !network.contains(destination)) throw new IllegalArgumentException("Introduce una IPv4 de tu misma red.");
            String message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
            byte[] bytes = message.getBytes(StandardCharsets.US_ASCII);
            DatagramPacket search = new DatagramPacket(bytes, bytes.length, destination, 1900);
            socket.send(search);
            long start = System.nanoTime(), lastSend = start;
            while ((System.nanoTime() - start) / 1_000_000 < 6500 && seen.size() < 32 && !Thread.currentThread().isInterrupted()) {
                if ((System.nanoTime() - lastSend) / 1_000_000 > 2200) { socket.send(search); lastSend = System.nanoTime(); }
                DatagramPacket reply = new DatagramPacket(new byte[16384], 16384);
                try { socket.receive(reply); } catch (SocketTimeoutException e) { continue; }
                if (!network.contains(reply.getAddress()) || (target != null && !reply.getAddress().equals(destination))) continue;
                String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.ISO_8859_1);
                if (!text.startsWith("HTTP/1.1 200")) continue;
                for (String line : text.split("\r\n")) {
                    int colon = line.indexOf(':');
                    if (colon < 0 || !line.substring(0, colon).equalsIgnoreCase("location")) continue;
                    String location = line.substring(colon + 1).trim(), peer = reply.getAddress().getHostAddress();
                    if (!seen.add(location)) continue;
                    work.add(descriptions.submit(() -> {
                        try {
                            URL url = UpnpXml.samePeer(new URL(location), peer);
                            for (DlnaDevice device : new DlnaClient(network::open).describe(url, peer)) found.accept(device);
                        } catch (Exception e) { warning.accept("Un dispositivo anunció DLNA pero no se pudo verificar: " + peer); }
                    }));
                }
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
            for (Future<?> future : work) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                try { future.get(left, TimeUnit.NANOSECONDS); } catch (ExecutionException | TimeoutException ignored) { }
            }
        } finally {
            descriptions.shutdownNow();
            if (lock.isHeld()) lock.release();
        }
    }
}
