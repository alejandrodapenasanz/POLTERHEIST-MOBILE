package org.polterheist.mobile;

import okhttp3.mockwebserver.*;
import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

/** A real loopback SOAP renderer simulator, not a physical TV compatibility claim. */
public class DlnaClientTest {
    private MockWebServer http;
    private String base;
    private final List<String> received=new CopyOnWriteArrayList<>();
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String description(String control) {
        return "<root xmlns='urn:schemas-upnp-org:device-1-0'><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:2</deviceType><friendlyName>TV de prueba</friendlyName><modelName>Simulador</modelName><UDN>uuid:test</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>"+control+"</controlURL><SCPDURL>/schema</SCPDURL></service></serviceList></device></root>";
    }
    @Before public void start() throws Exception {
        http=new MockWebServer();
        http.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
            String result;
            if(request.getPath().equals("/description"))result=description("/control");
            else if(request.getPath().equals("/schema")){
                StringBuilder schema=new StringBuilder("<scpd xmlns='urn:schemas-upnp-org:service-1-0'><actionList>");
                for(String name:List.of("SetAVTransportURI","Play","Pause","Stop","GetTransportInfo"))schema.append("<action><name>").append(name).append("</name></action>");
                result=schema+"</actionList></scpd>";
            }else{
                String body=request.getBody().readUtf8();
                String action=request.getHeader("SOAPAction");
                String name=action.substring(action.indexOf('#')+1,action.length()-1);
                received.add(name+":"+body);
                result="<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body><u:"+name+"Response xmlns:u='urn:schemas-upnp-org:service:AVTransport:1'>"+
                        (name.equals("GetTransportInfo")?"<CurrentTransportState>PLAYING</CurrentTransportState>":"")+"</u:"+name+"Response></s:Body></s:Envelope>";
            }
            return new MockResponse().setResponseCode(200).setHeader("Content-Type","text/xml").setBody(result);
        }});
        http.start(InetAddress.getByName("127.0.0.1"),0);
        base="http://127.0.0.1:"+http.getPort();
    }
    @After public void stop() throws Exception {http.shutdown();}
    @Test public void describesLoadsAndControlsRenderer() throws Exception {
        DlnaClient client=new DlnaClient(url->(HttpURLConnection)url.openConnection(Proxy.NO_PROXY));
        DlnaDevice device=client.describe(new URL(base+"/description"),"127.0.0.1").get(0);
        assertEquals("TV de prueba",device.name);assertTrue(device.playable());
        File file=File.createTempFile("test-media-",".mp4");
        try{
            client.load(device,new MediaFile(file,"A&B <photo>","video/mp4",1),"http://127.0.0.1/media/token.mp4");
            assertTrue(received.get(0).startsWith("SetAVTransportURI:"));assertTrue(received.get(0).contains("A&amp;amp;B &amp;lt;photo&amp;gt;"));
            assertTrue(received.get(1).startsWith("Play:"));
            assertEquals("PLAYING",client.state(device));client.pause(device);client.stop(device);
            assertTrue(received.get(3).startsWith("Pause:"));assertTrue(received.get(4).startsWith("Stop:"));
            assertThrows(IOException.class,()->client.action(device,"Delete",Map.of()));
        }finally{file.delete();}
    }
    @Test public void rejectsCrossHostControlAndDescriptions() throws Exception {
        assertThrows(IOException.class,()->UpnpXml.devices(bytes(description("http://192.0.2.1/control")),new URL(base+"/description"),"127.0.0.1"));
        assertThrows(IOException.class,()->UpnpXml.devices(bytes(description("/control")),new URL("http://192.0.2.1/description"),"127.0.0.1"));
        assertThrows(IOException.class,()->UpnpXml.samePeer(new URL("http://user:password@127.0.0.1/control"),"127.0.0.1"));
    }
    @Test public void rejectsExternalEntities() {
        assertThrows(IOException.class,()->UpnpXml.parse(bytes("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///secret'>]><root>&x;</root>")));
        assertThrows(IOException.class,()->UpnpXml.parse(new byte[]{'<',0,'r',0,'>'}));
    }
    @Test public void nonRendererIsNotReportedAsTv() throws Exception {
        String text=description("/control").replace("MediaRenderer:2","InternetGatewayDevice:1");
        assertTrue(UpnpXml.devices(bytes(text),new URL(base+"/description"),"127.0.0.1").isEmpty());
    }
    @Test public void soapFaultAndMissingConfirmationAreErrors() {
        assertThrows(IOException.class,()->UpnpXml.response(bytes("<root><Fault>Unsupported format</Fault></root>"),"Play"));
        assertThrows(IOException.class,()->UpnpXml.response(bytes("<root/>"),"Play"));
    }
}
