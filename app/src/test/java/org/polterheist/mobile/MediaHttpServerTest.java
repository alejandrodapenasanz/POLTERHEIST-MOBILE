package org.polterheist.mobile;

import org.junit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MediaHttpServerTest {
    private File file;
    private MediaHttpServer server;
    private String url;
    @Before public void start() throws Exception {
        file=File.createTempFile("polterheist-test-", ".mp4");
        Files.write(file.toPath(),"0123456789".getBytes(StandardCharsets.US_ASCII));
        server=new MediaHttpServer(InetAddress.getByName("127.0.0.1"),"<script>var state='__STATE_PATH__';</script>");
        url=server.select(new MediaFile(file,"a\"<&.mp4","video/mp4",1000),false);
    }
    @After public void stop(){server.close();file.delete();}
    private HttpURLConnection request(String address,String method,String range) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(2000);c.setReadTimeout(2000);c.setRequestMethod(method);
        if(range!=null)c.setRequestProperty("Range",range);return c;
    }
    private String body(HttpURLConnection c) throws Exception {
        try(InputStream in=c.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}finally{c.disconnect();}
    }
    @Test public void streamsOnlySelectedFile() throws Exception {
        HttpURLConnection c=request(url,"GET",null);
        assertEquals(200,c.getResponseCode());assertEquals("video/mp4",c.getContentType());
        assertEquals("*",c.getHeaderField("Access-Control-Allow-Origin"));assertEquals("0123456789",body(c));
    }
    @Test public void supportsSeeking() throws Exception {
        HttpURLConnection c=request(url,"GET","bytes=2-5");
        assertEquals(206,c.getResponseCode());assertEquals("bytes 2-5/10",c.getHeaderField("Content-Range"));assertEquals(4,c.getContentLength());assertEquals("2345",body(c));
        assertEquals("789",body(request(url,"GET","bytes=-3")));
    }
    @Test public void headHasHeadersWithoutBody() throws Exception {
        HttpURLConnection c=request(url,"HEAD",null);
        assertEquals(200,c.getResponseCode());assertEquals(10,c.getContentLength());assertEquals("",body(c));
    }
    @Test public void invalidRangeReturns416() throws Exception {
        HttpURLConnection c=request(url,"GET","bytes=10-");
        assertEquals(416,c.getResponseCode());assertEquals("bytes */10",c.getHeaderField("Content-Range"));c.disconnect();
    }
    @Test public void rejectsPathsAndMethods() throws Exception {
        for(String path:new String[]{"/", "/media/file.mp4", "/../secret", "/tv/bad", "/state/bad"}) {
            HttpURLConnection c=request(server.baseUrl()+path,"GET",null);assertEquals(404,c.getResponseCode());c.disconnect();
        }
        HttpURLConnection c=request(url,"POST",null);assertEquals(405,c.getResponseCode());c.disconnect();
    }
    @Test public void newSelectionRevokesOldUrl() throws Exception {
        server.select(new MediaFile(file,"new","video/mp4",1),false);
        HttpURLConnection c=request(url,"GET",null);assertEquals(404,c.getResponseCode());c.disconnect();
    }
    @Test public void browserReceivesControlsAndEscapedName() throws Exception {
        String html=body(request(server.viewerUrl(),"GET",null));
        assertFalse(html.contains("__STATE_PATH__"));
        String stateUrl=server.viewerUrl().replace("/tv/","/state/");
        server.command("pause");server.setLoop(true);
        String state=body(request(stateUrl,"GET",null));
        assertTrue(state.contains("\"command\":\"pause\""));assertTrue(state.contains("\"loop\":true"));
        assertTrue(state.contains("a\\\"\\u003c&.mp4"));assertTrue(server.hasViewer());
        server.command("stop");
        assertTrue(body(request(stateUrl,"GET",null)).contains("\"command\":\"stop\""));
        HttpURLConnection c=request(url,"GET",null);assertEquals(404,c.getResponseCode());c.disconnect();
    }
    @Test public void closingRevokesServer() {
        server.close();assertThrows(IOException.class,()->body(request(url,"GET",null)));
    }
    @Test public void mediaTypesCannotInjectHeaders() {
        assertThrows(IllegalArgumentException.class,()->new MediaFile(file,"name","video/mp4\r\nX-Injected: 1",0));
    }
}
