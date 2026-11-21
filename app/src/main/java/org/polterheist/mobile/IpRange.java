package org.polterheist.mobile;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses user input for targeted discovery on any (routed) private subnet:
 * "192.168.2.40", "192.168.2.0/24" (prefix 22..32) or "192.168.2.10-60" (last-octet range).
 * Only private (RFC 1918) addresses are accepted to avoid probing the Internet.
 */
public final class IpRange {
    public static final int MAX_HOSTS = 1024;
    private IpRange() { }

    public static List<Inet4Address> parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        List<Inet4Address> result = new ArrayList<>();
        try {
            if (text.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
                result.add(v4(text));
            } else if (text.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}")) {
                String[] parts = text.split("/");
                int prefix = Integer.parseInt(parts[1]);
                if (prefix < 22 || prefix > 32) throw new IllegalArgumentException("Usa un prefijo entre /22 y /32.");
                long base = toLong(v4(parts[0])) & (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long size = 1L << (32 - prefix);
                for (long i = 0; i < size; i++) {
                    if (size > 2 && (i == 0 || i == size - 1)) continue; // network / broadcast
                    result.add(fromLong(base + i));
                }
            } else if (text.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}-[0-9]{1,3}")) {
                String[] parts = text.split("-");
                Inet4Address first = v4(parts[0]);
                int from = first.getAddress()[3] & 0xFF, to = Integer.parseInt(parts[1]);
                if (to > 255 || to < from) throw new IllegalArgumentException("Rango no válido.");
                long base = toLong(first) & 0xFFFFFF00L;
                for (int i = from; i <= to; i++) result.add(fromLong(base + i));
            } else {
                throw new IllegalArgumentException("Escribe una IP (192.168.2.40), una red (192.168.2.0/24) o un rango (192.168.2.10-60).");
            }
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Dirección no válida.");
        }
        if (result.isEmpty() || result.size() > MAX_HOSTS) throw new IllegalArgumentException("El rango debe tener entre 1 y " + MAX_HOSTS + " direcciones.");
        for (Inet4Address address : result) {
            if (!address.isSiteLocalAddress()) throw new IllegalArgumentException("Solo se admiten direcciones privadas (10.x, 172.16-31.x, 192.168.x).");
        }
        return result;
    }

    private static Inet4Address v4(String text) throws UnknownHostException {
        for (String part : text.split("\\.")) if (Integer.parseInt(part) > 255) throw new UnknownHostException(text);
        return (Inet4Address) InetAddress.getByName(text);
    }
    private static long toLong(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xFFL) << 24) | ((b[1] & 0xFFL) << 16) | ((b[2] & 0xFFL) << 8) | (b[3] & 0xFFL);
    }
    private static Inet4Address fromLong(long v) throws UnknownHostException {
        return (Inet4Address) InetAddress.getByAddress(new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v});
    }
}
