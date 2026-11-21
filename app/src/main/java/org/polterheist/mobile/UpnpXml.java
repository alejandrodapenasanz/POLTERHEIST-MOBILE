package org.polterheist.mobile;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

public final class UpnpXml {
    static Document parse(byte[] bytes) throws Exception {
        if (bytes.length > 524288) throw new IOException("Descripción demasiado grande");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String upper = text.toUpperCase(Locale.ROOT);
        if (text.indexOf('\0') >= 0 || upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("XML con entidades no admitidas");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External entity blocked"); });
        return builder.parse(new InputSource(new StringReader(text)));
    }

    static String value(Element element, String name) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && name.equals(node.getLocalName())) return node.getTextContent().trim();
        }
        return "";
    }

    static URL samePeer(URL url, String peer) throws IOException {
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https")) ||
                !url.getHost().equals(peer) || url.getUserInfo() != null || url.getRef() != null ||
                (url.getPort() != -1 && (url.getPort() == 0 || url.getPort() > 65535))) {
            throw new IOException("La dirección anunciada no pertenece al dispositivo");
        }
        return url;
    }

    public static List<DlnaDevice> devices(byte[] bytes, URL location, String peer) throws Exception {
        samePeer(location, peer);
        Document doc = parse(bytes);
        String baseText = value(doc.getDocumentElement(), "URLBase");
        URL base = baseText.isEmpty() ? location : samePeer(new URL(baseText), peer);
        List<DlnaDevice> result = new ArrayList<>();
        NodeList devices = doc.getElementsByTagNameNS("*", "device");
        for (int i = 0; i < Math.min(devices.getLength(), 32); i++) {
            Element device = (Element) devices.item(i);
            if (!value(device, "deviceType").matches("urn:schemas-upnp-org:device:MediaRenderer:[0-9]+")) continue;
            for (Node node = device.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element) || !"serviceList".equals(node.getLocalName())) continue;
                for (Node service = node.getFirstChild(); service != null; service = service.getNextSibling()) {
                    if (!(service instanceof Element)) continue;
                    Element entry = (Element) service;
                    String type = value(entry, "serviceType");
                    if (!type.matches("urn:schemas-upnp-org:service:AVTransport:[0-9]+")) continue;
                    String control = value(entry, "controlURL"), schema = value(entry, "SCPDURL");
                    if (control.isEmpty() || schema.isEmpty()) continue;
                    result.add(new DlnaDevice(value(device, "UDN"), value(device, "friendlyName"),
                            value(device, "modelName"), peer, type,
                            samePeer(new URL(base, control), peer), samePeer(new URL(base, schema), peer), Collections.emptySet()));
                }
            }
        }
        return result;
    }

    public static Set<String> actions(byte[] bytes) throws Exception {
        NodeList list = parse(bytes).getElementsByTagNameNS("*", "action");
        Set<String> result = new HashSet<>();
        for (int i = 0; i < Math.min(list.getLength(), 128); i++) result.add(value((Element) list.item(i), "name"));
        return result;
    }

    public static Map<String, String> response(byte[] bytes, String action) throws Exception {
        Document doc = parse(bytes);
        NodeList fault = doc.getElementsByTagNameNS("*", "Fault");
        if (fault.getLength() > 0) throw new IOException("La TV rechazó " + action + ": " + fault.item(0).getTextContent().trim());
        NodeList responses = doc.getElementsByTagNameNS("*", action + "Response");
        if (responses.getLength() == 0) throw new IOException("La TV no confirmó " + action);
        Map<String, String> result = new HashMap<>();
        for (Node node = responses.item(0).getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element) result.put(node.getLocalName(), node.getTextContent().trim());
        }
        return result;
    }

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    public static String metadata(MediaFile media, String url) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>" + escape(media.name) + "</dc:title><upnp:class>" +
                (media.isImage() ? "object.item.imageItem.photo" : "object.item.videoItem") + "</upnp:class><res protocolInfo=\"http-get:*:" +
                escape(media.mime) + ":*\" size=\"" + media.file.length() + "\">" + escape(url) + "</res></item></DIDL-Lite>";
    }
}
