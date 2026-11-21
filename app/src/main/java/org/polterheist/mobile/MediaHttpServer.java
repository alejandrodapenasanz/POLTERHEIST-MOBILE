package org.polterheist.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Only the selected media is addressable. No filesystem paths are accepted from clients. */
public final class MediaHttpServer implements Closeable {
    private final ServerSocket socket;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(4, 4, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(12));
    private final Set<Socket> openClients = ConcurrentHashMap.newKeySet();
    private final String viewerCode = randomToken(6);
    private final String html;
    private final String host;
    private volatile Selection selected;
    private volatile String command = "play";
    private volatile boolean loop;
    private volatile boolean closed;
    private volatile long viewerSeenAt;

    private static final class Selection {
        final MediaFile media;
        final String path;
        Selection(MediaFile media) {
            this.media = media;
            String extension = media.isImage() ? ".jpg" : switch (media.mime) {
                case "video/webm" -> ".webm";
                case "video/x-matroska" -> ".mkv";
                case "video/quicktime" -> ".mov";
                case "video/mp4" -> ".mp4";
                default -> ".video";
            };
            this.path = "/media/" + randomToken(16) + extension;
        }
    }

    public MediaHttpServer(InetAddress address, String html) throws IOException {
        this.html = html;
        this.host = address.getHostAddress();
        socket = new ServerSocket();
        socket.bind(new InetSocketAddress(address, 0));
        Thread accept = new Thread(this::accept, "polterheist-http");
        accept.setDaemon(true);
        accept.start();
    }

    public synchronized String select(MediaFile media, boolean loop) {
        selected = new Selection(media);
        command = "play";
        this.loop = loop;
        return baseUrl() + selected.path;
    }

    public void command(String command) {
        if (!Arrays.asList("play", "pause", "stop").contains(command)) throw new IllegalArgumentException();
        this.command = command;
    }
    public void setLoop(boolean loop) { this.loop = loop; }
    public String baseUrl() { return "http://" + host + ":" + socket.getLocalPort(); }
    public String viewerUrl() { return baseUrl() + "/tv/" + viewerCode; }
    public boolean hasViewer() { return System.currentTimeMillis() - viewerSeenAt < 5000; }

    private void accept() {
        while (!closed) {
            try {
                Socket client = socket.accept();
                client.setSoTimeout(10000);
                openClients.add(client);
                try { clients.execute(() -> serve(client)); }
                catch (RejectedExecutionException e) { openClients.remove(client); client.close(); }
            } catch (IOException ignored) { if (!closed) close(); }
        }
    }

    private void serve(Socket client) {
        try (Socket ignored = client;
             InputStream input = new BufferedInputStream(client.getInputStream());
             OutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            String first = line(input);
            String[] request = first.split(" ");
            if (request.length != 3) return;
            Map<String, String> headers = new HashMap<>();
            int total = first.length();
            for (int i = 0; i < 64; i++) {
                String row = line(input);
                total += row.length();
                if (total > 16384) return;
                if (row.isEmpty()) break;
                int colon = row.indexOf(':');
                if (colon > 0) headers.put(row.substring(0, colon).toLowerCase(Locale.ROOT), row.substring(colon + 1).trim());
                if (i == 63) return;
            }
            String method = request[0], path = request[1];
            boolean head = method.equals("HEAD");
            if (method.equals("OPTIONS")) {
                reply(output, "204 No Content", "text/plain", new byte[0], false, "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\n");
            } else if (!method.equals("GET") && !head) {
                reply(output, "405 Method Not Allowed", "text/plain", new byte[0], head, "Allow: GET, HEAD, OPTIONS\r\n");
            } else if (path.equals("/tv/" + viewerCode)) {
                reply(output, "200 OK", "text/html; charset=utf-8", html.replace("__STATE_PATH__", "/state/" + viewerCode).getBytes(StandardCharsets.UTF_8), head, "");
            } else if (path.equals("/state/" + viewerCode)) {
                if (!head) viewerSeenAt = System.currentTimeMillis();
                Selection current = selected;
                String state = current == null ? "{\"command\":\"stop\"}" :
                        "{\"url\":" + json(current.path) + ",\"name\":" + json(current.media.name) +
                        ",\"image\":" + current.media.isImage() + ",\"loop\":" + loop + ",\"command\":" + json(command) + "}";
                reply(output, "200 OK", "application/json", state.getBytes(StandardCharsets.UTF_8), head, "");
            } else {
                Selection current = selected;
                if (current == null || !path.equals(current.path) || command.equals("stop")) {
                    reply(output, "404 Not Found", "text/plain", new byte[0], head, "");
                } else {
                    sendFile(output, current.media, headers.get("range"), head);
                }
            }
            output.flush();
        } catch (IOException ignored) {
            // TVs routinely cancel prefetch and range requests when seeking or stopping.
        } finally { openClients.remove(client); }
    }

    private static void sendFile(OutputStream output, MediaFile media, String range, boolean head) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(media.file, "r")) {
            long size = file.length();
            ByteRange bytes;
            try { bytes = ByteRange.parse(range, size); }
            catch (IllegalArgumentException e) {
                reply(output, "416 Range Not Satisfiable", "text/plain", new byte[0], head, "Content-Range: bytes */" + size + "\r\n");
                return;
            }
            String extra = "Accept-Ranges: bytes\r\ntransferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0\r\n";
            if (bytes.partial) extra += "Content-Range: bytes " + bytes.start + "-" + bytes.end + "/" + size + "\r\n";
            header(output, bytes.partial ? "206 Partial Content" : "200 OK", media.mime, bytes.length(), extra);
            if (!head) {
                file.seek(bytes.start);
                byte[] buffer = new byte[64 * 1024];
                long remaining = bytes.length();
                while (remaining > 0) {
                    int count = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) break;
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
        }
    }

    private static void reply(OutputStream out, String status, String type, byte[] body, boolean head, String extra) throws IOException {
        header(out, status, type, body.length, extra);
        if (!head) out.write(body);
    }

    private static void header(OutputStream out, String status, String type, long size, String extra) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + size +
                "\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n" +
                "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n" +
                "X-Content-Type-Options: nosniff\r\n" + extra + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int c = in.read();
            if (c == -1) throw new EOFException();
            if (c == '\n') return line.toString("ISO-8859-1").replace("\r", "");
            line.write(c);
        }
        throw new IOException("Header too long");
    }

    static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || c == '<' || c == '>') out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        StringBuilder token = new StringBuilder();
        for (byte b : value) token.append(String.format(Locale.ROOT, "%02x", b & 255));
        return token.toString();
    }

    @Override public void close() {
        closed = true;
        selected = null;
        try { socket.close(); } catch (IOException ignored) { }
        for (Socket client : openClients) try { client.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
    }
}
