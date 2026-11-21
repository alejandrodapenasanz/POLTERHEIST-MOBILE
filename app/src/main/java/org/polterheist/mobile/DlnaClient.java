package org.polterheist.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class DlnaClient {
    public interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private final ConnectionFactory connections;
    public DlnaClient(ConnectionFactory connections) { this.connections = connections; }

    public List<DlnaDevice> describe(URL location, String peer) throws Exception {
        List<DlnaDevice> result = new ArrayList<>();
        for (DlnaDevice device : UpnpXml.devices(request(UpnpXml.samePeer(location, peer), null, null), location, peer)) {
            result.add(device.withActions(UpnpXml.actions(request(device.schema, null, null))));
        }
        return result;
    }

    public Map<String, String> action(DlnaDevice device, String name, Map<String, String> arguments) throws Exception {
        if (!device.actions.contains(name)) throw new IOException("Esta TV no anuncia la función " + name);
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:")
                .append(name).append(" xmlns:u=\"").append(UpnpXml.escape(device.serviceType)).append("\">");
        for (var entry : arguments.entrySet()) body.append('<').append(entry.getKey()).append('>').append(UpnpXml.escape(entry.getValue())).append("</").append(entry.getKey()).append('>');
        body.append("</u:").append(name).append("></s:Body></s:Envelope>");
        return UpnpXml.response(request(device.control, device.serviceType + "#" + name, body.toString()), name);
    }

    public void load(DlnaDevice device, MediaFile media, String url) throws Exception {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("InstanceID", "0"); args.put("CurrentURI", url); args.put("CurrentURIMetaData", UpnpXml.metadata(media, url));
        action(device, "SetAVTransportURI", args);
        play(device);
    }

    public void play(DlnaDevice device) throws Exception {
        LinkedHashMap<String, String> args = new LinkedHashMap<>();
        args.put("InstanceID", "0"); args.put("Speed", "1");
        action(device, "Play", args);
    }
    public void pause(DlnaDevice device) throws Exception { action(device, "Pause", Map.of("InstanceID", "0")); }
    public void stop(DlnaDevice device) throws Exception { action(device, "Stop", Map.of("InstanceID", "0")); }
    public String state(DlnaDevice device) throws Exception {
        return action(device, "GetTransportInfo", Map.of("InstanceID", "0")).getOrDefault("CurrentTransportState", "UNKNOWN");
    }

    private byte[] request(URL url, String soapAction, String body) throws IOException {
        HttpURLConnection connection = connections.open(url);
        connection.setConnectTimeout(3500);
        connection.setReadTimeout(3500);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Connection", "close");
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
                connection.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("Redirección de TV no admitida");
            InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IOException("La TV respondió HTTP " + status);
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > 524288) throw new IOException("Respuesta de TV demasiado grande");
                    output.write(buffer, 0, n);
                }
                if (status != 200 && body == null) throw new IOException("La TV respondió HTTP " + status);
                return output.toByteArray();
            }
        } finally { connection.disconnect(); }
    }
}
