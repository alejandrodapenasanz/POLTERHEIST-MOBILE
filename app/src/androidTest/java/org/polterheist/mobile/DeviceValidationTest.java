package org.polterheist.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;
import android.os.Bundle;
import android.util.Log;
import androidx.lifecycle.Lifecycle;
import androidx.mediarouter.media.MediaRouter;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Device-only checks: real MediaCodec, Android HTTP sockets and foreground service. */
@RunWith(AndroidJUnit4.class)
public class DeviceValidationTest {
    private Context context;
    private MediaFile image;

    @Before public void createFixture() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "device-validation");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = File.createTempFile("color-test-", ".jpg", directory);
        Bitmap bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.RED); canvas.drawRect(0, 0, 320, 360, paint);
        paint.setColor(Color.GREEN); canvas.drawRect(320, 0, 640, 360, paint);
        try (OutputStream output = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)); }
        bitmap.recycle();
        image = new MediaFile(file, "POLTERHEIST · prueba de color", "image/jpeg", 0);
    }

    @After public void cleanFixture() { if (image != null) image.file.delete(); }

    @Test public void nativeEncoderCreatesDecodableH264WithCorrectColors() throws Exception {
        MediaFile clip = ImageClipEncoder.encode(image);
        try {
            assertTrue("El codificador debe producir un MP4", clip.file.length() > 1024);
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(clip.file.getPath());
                assertEquals(1, extractor.getTrackCount());
                MediaFormat format = extractor.getTrackFormat(0);
                assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME));
                assertEquals(1280, format.getInteger(MediaFormat.KEY_WIDTH));
                assertEquals(720, format.getInteger(MediaFormat.KEY_HEIGHT));
                extractor.selectTrack(0);
                int frames = 0;
                long last = -1;
                ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
                while (extractor.readSampleData(buffer, 0) >= 0) {
                    assertTrue("Marcas de tiempo monotónicas", extractor.getSampleTime() >= last);
                    last = extractor.getSampleTime(); frames++;
                    extractor.advance(); buffer.clear();
                }
                assertEquals(60, frames);
                assertTrue(last > 1_900_000);
            } finally { extractor.release(); }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(clip.file.getPath());
                Bitmap decoded = retriever.getFrameAtTime(1_000_000);
                assertNotNull("El decodificador del móvil debe leer el clip", decoded);
                try {
                    int red = decoded.getPixel(decoded.getWidth() / 4, decoded.getHeight() / 2);
                    int green = decoded.getPixel(decoded.getWidth() * 3 / 4, decoded.getHeight() / 2);
                    assertTrue("El plano rojo debe conservar el color", Color.red(red) > 180 && Color.green(red) < 80 && Color.blue(red) < 80);
                    assertTrue("El plano verde debe conservar el color", Color.green(green) > 180 && Color.red(green) < 80 && Color.blue(green) < 80);
                } finally { decoded.recycle(); }
            } finally { retriever.release(); }
            if ("true".equals(InstrumentationRegistry.getArguments().getString("exportMedia"))) {
                File export = context.getExternalFilesDir("validation");
                assertNotNull(export);
                copy(image.file, new File(export, "POLTERHEIST-prueba.jpg"));
                copy(clip.file, new File(export, "POLTERHEIST-prueba.mp4"));
                report("MEDIA_EXPORTED " + export.getAbsolutePath());
            }
        } finally { clip.file.delete(); }
    }

    @Test public void nativeHttpSupportsRangeAndRevokesStoppedMedia() throws Exception {
        try (MediaHttpServer server = new MediaHttpServer(InetAddress.getByName("127.0.0.1"), "__STATE_PATH__")) {
            String url = server.select(image, false);
            HttpURLConnection connection = open(url);
            connection.setRequestProperty("Range", "bytes=0-15");
            try {
                assertEquals(206, connection.getResponseCode());
                assertEquals(16, connection.getContentLength());
                try (InputStream input = connection.getInputStream()) {
                    assertEquals(255, input.read()); assertEquals(216, input.read()); // JPEG SOI
                }
            } finally { connection.disconnect(); }
            server.command("stop");
            connection = open(url);
            try { assertEquals(404, connection.getResponseCode()); }
            finally { connection.disconnect(); }
        }
    }

    @Test public void foregroundBrowserSessionSurvivesActivityRecreationAndStops() throws Exception {
        checkBrowserSession(image);
    }

    @Test public void foregroundVideoSupportsPauseResumeLoopAndBackground() throws Exception {
        MediaFile clip = ImageClipEncoder.encode(image);
        try { checkBrowserSession(clip); }
        finally { clip.file.delete(); }
    }

    private void checkBrowserSession(MediaFile fixture) throws Exception {
        try { LocalNetwork.find(context); }
        catch (IOException noNetwork) { Assume.assumeNoException("Necesita Wi-Fi o Ethernet local", noNetwork); }
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            AppController controller = ((PolterApp) context.getApplicationContext()).controller;
            activity.onActivity(a -> {
                assertFalse(controller.sharing);
                controller.media = fixture;
                controller.chooseBrowser();
                controller.setLoop(!fixture.isImage());
                controller.send();
            });
            try {
                String url = awaitViewer(activity, controller);
                String state = body(open(url.replace("/tv/", "/state/")));
                assertTrue(state.contains("\"image\":" + fixture.isImage()));
                assertTrue(state.contains("\"command\":\"play\""));
                if (!fixture.isImage()) {
                    assertTrue(state.contains("\"loop\":true"));
                    activity.onActivity(a -> controller.togglePause());
                    assertTrue(body(open(url.replace("/tv/", "/state/"))).contains("\"command\":\"pause\""));
                    activity.onActivity(a -> controller.togglePause());
                    assertTrue(body(open(url.replace("/tv/", "/state/"))).contains("\"command\":\"play\""));
                }
                activity.recreate();
                assertEquals(url, awaitViewer(activity, controller));
                activity.moveToState(Lifecycle.State.CREATED);
                SystemClock.sleep(2500);
                assertTrue(body(open(url)).contains("POLTERHEIST"));
                activity.moveToState(Lifecycle.State.RESUMED);
                report("BROWSER_SESSION_OK " + (fixture.isImage() ? "image" : "video") + " url=" + url);
            } finally {
                activity.onActivity(a -> controller.stop());
                long deadline = SystemClock.elapsedRealtime() + 8000;
                AtomicReference<Boolean> active = new AtomicReference<>(true);
                do {
                    activity.onActivity(a -> active.set(controller.sharing));
                    if (!active.get()) break;
                    SystemClock.sleep(100);
                } while (SystemClock.elapsedRealtime() < deadline);
                assertFalse("El servicio debe detenerse", active.get());
                activity.onActivity(a -> { controller.media = null; controller.target = AppController.Target.NONE; controller.targetName = "Ninguna TV seleccionada"; });
            }
        }
    }

    @Test public void discoverReceiversWithoutStartingPlayback() throws Exception {
        try { LocalNetwork.find(context); }
        catch (IOException noNetwork) { Assume.assumeNoException("Necesita red local", noNetwork); }
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            AppController controller = ((PolterApp) context.getApplicationContext()).controller;
            activity.onActivity(a -> controller.scan(null));
            awaitMain(activity, () -> !controller.scanning, 25000, "La búsqueda no terminó");
            activity.onActivity(a -> {
                report("DISCOVERY_NETWORK " + controller.networkLabel);
                report("DISCOVERY_NOTE " + controller.scanNote);
                for (DlnaDevice device : controller.devices) report("DLNA_RECEIVER " + device.name + " ip=" + device.host + " playable=" + device.playable());
                if (controller.cast != null) {
                    report("CAST_STATUS " + controller.cast.unavailable);
                    for (MediaRouter.RouteInfo route : controller.cast.routes) report("CAST_RECEIVER " + route.getName());
                }
                assertFalse("Detectar no debe iniciar emisión", controller.sharing);
            });
        }
    }

    /** Opt-in only: exact receiver name is supplied explicitly through instrumentation args. */
    @Test public void castImageAndVideoWhenTargetRequested() throws Exception {
        String name = InstrumentationRegistry.getArguments().getString("castTarget");
        Assume.assumeTrue("Sin receptor Cast solicitado: no se controla ninguna TV", name != null && !name.isEmpty());
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
            AppController controller = ((PolterApp) context.getApplicationContext()).controller;
            activity.onActivity(a -> controller.scan(null));
            MediaFile clip = ImageClipEncoder.encode(image);
            try {
                String kind = InstrumentationRegistry.getArguments().getString("castMedia", "both");
                int holdSeconds = Math.max(3, Math.min(90, Integer.parseInt(InstrumentationRegistry.getArguments().getString("holdSeconds", "10"))));
                MediaFile[] fixtures = "image".equals(kind) ? new MediaFile[]{image} : "video".equals(kind) ? new MediaFile[]{clip} : new MediaFile[]{image, clip};
                for (MediaFile fixture : fixtures) {
                    AtomicReference<MediaRouter.RouteInfo> route = new AtomicReference<>();
                    activity.onActivity(a -> controller.scan(null));
                    // Cast replaces route objects after disconnect; always resolve a current one.
                    awaitMain(activity, () -> {
                        if (controller.cast == null) return false;
                        for (MediaRouter.RouteInfo item : controller.cast.routes) if (name.trim().equals(item.getName().trim())) route.set(item);
                        return route.get() != null;
                    }, 30000, "No apareció el receptor Cast solicitado: " + name);
                    activity.onActivity(a -> controller.chooseCast(route.get()));
                    awaitMain(activity, () -> controller.cast.connected(), 35000, "No se conectó Cast");
                    activity.onActivity(a -> {
                        controller.media = fixture;
                        controller.setLoop(true);
                        controller.send();
                    });
                    awaitMain(activity, () -> playerState() == MediaStatus.PLAYER_STATE_PLAYING, 50000, "La TV no confirmó PLAYING: " + controller.status);
                    report("CAST_PLAYING " + name + " kind=" + (fixture.isImage() ? "image" : "video"));
                    SystemClock.sleep(holdSeconds * 1000L);
                    if (!fixture.isImage()) {
                        activity.onActivity(a -> controller.togglePause());
                        awaitMain(activity, () -> playerState() == MediaStatus.PLAYER_STATE_PAUSED, 15000, "La TV no confirmó pausa");
                        activity.onActivity(a -> controller.togglePause());
                        awaitMain(activity, () -> playerState() == MediaStatus.PLAYER_STATE_PLAYING, 15000, "La TV no confirmó reanudación");
                        report("CAST_PAUSE_RESUME_OK " + name);
                    }
                    activity.onActivity(a -> controller.stop());
                    awaitMain(activity, () -> !controller.sharing && !controller.cast.connected(), 15000, "La sesión Cast no se cerró");
                }
            } finally {
                activity.onActivity(a -> controller.stop());
                clip.file.delete();
            }
        }
    }

    private int playerState() {
        CastSession session = CastContext.getSharedInstance(context).getSessionManager().getCurrentCastSession();
        if (session == null || session.getRemoteMediaClient() == null) return MediaStatus.PLAYER_STATE_UNKNOWN;
        return session.getRemoteMediaClient().getPlayerState();
    }
    private void awaitMain(ActivityScenario<MainActivity> activity, BooleanSupplier condition, long timeout, String error) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        while (SystemClock.elapsedRealtime() < deadline) {
            activity.onActivity(a -> result.set(condition.getAsBoolean()));
            if (result.get()) return;
            SystemClock.sleep(150);
        }
        fail(error);
    }
    private static void report(String text) {
        Log.i("PolterDeviceTest", text);
        Bundle result = new Bundle(); result.putString("stream", "\n" + text + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
    private static void copy(File source, File target) throws IOException {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    private String awaitViewer(ActivityScenario<MainActivity> activity, AppController controller) {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        AtomicReference<String> url = new AtomicReference<>("");
        while (SystemClock.elapsedRealtime() < deadline) {
            activity.onActivity(a -> url.set(controller.viewerUrl()));
            if (!url.get().isEmpty()) return url.get();
            SystemClock.sleep(100);
        }
        fail("No arrancó la sesión de navegador: " + controller.status);
        return "";
    }
    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
        return connection;
    }
    private static String body(HttpURLConnection connection) throws Exception {
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int read;
            while ((read = input.read(bytes)) != -1) output.write(bytes, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        } finally { connection.disconnect(); }
    }
}
