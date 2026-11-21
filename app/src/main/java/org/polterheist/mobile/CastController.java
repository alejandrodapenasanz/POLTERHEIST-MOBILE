package org.polterheist.mobile;

import android.content.Context;
import androidx.mediarouter.media.*;
import com.google.android.gms.cast.*;
import com.google.android.gms.cast.framework.*;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CastController {
    public interface Listener {
        void changed();
        void session(boolean connected, String name);
        void status(String text);
    }
    public final List<MediaRouter.RouteInfo> routes = new ArrayList<>();
    public String unavailable = "Preparando Google Cast…";
    private CastContext cast;
    private MediaRouter router;
    private final Listener listener;
    private boolean discovering;
    private RemoteMediaClient observed;
    private final MediaRouteSelector selector = new MediaRouteSelector.Builder().addControlCategory(
            CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build();
    private final MediaRouter.Callback routeCallback = new MediaRouter.Callback() {
        @Override public void onRouteAdded(MediaRouter router, MediaRouter.RouteInfo route) { refresh(); }
        @Override public void onRouteRemoved(MediaRouter router, MediaRouter.RouteInfo route) { refresh(); }
        @Override public void onRouteChanged(MediaRouter router, MediaRouter.RouteInfo route) { refresh(); }
    };
    private final RemoteMediaClient.Callback mediaCallback = new RemoteMediaClient.Callback() {
        @Override public void onStatusUpdated() {
            RemoteMediaClient client = client();
            if (client == null) return;
            if (client.isPlaying()) listener.status("La TV confirma reproducción");
            else if (client.isPaused()) listener.status("En pausa");
            else if (client.isBuffering()) listener.status("La TV está cargando el contenido…");
            else if (client.getPlayerState() == MediaStatus.PLAYER_STATE_IDLE) {
                MediaStatus status = client.getMediaStatus();
                if (status != null && status.getIdleReason() == MediaStatus.IDLE_REASON_ERROR) listener.status("La TV no pudo reproducir el archivo. Prueba un MP4 H.264/AAC.");
                else listener.status("Reproducción finalizada");
            }
        }
    };

    public CastController(Context context, Listener listener) {
        this.listener = listener;
        var executor = Executors.newSingleThreadExecutor();
        try {
            CastContext.getSharedInstance(context, executor).addOnSuccessListener(value -> {
                executor.shutdown();
                cast = value;
                router = MediaRouter.getInstance(context);
                unavailable = "";
                cast.getSessionManager().addSessionManagerListener(sessions, CastSession.class);
                setDiscovering(discovering);
                refresh();
            }).addOnFailureListener(error -> {
                executor.shutdown();
                unavailable = "Cast necesita Google Play Services disponible y actualizado. DLNA y navegador siguen disponibles.";
                listener.changed();
            });
        } catch (RuntimeException e) {
            executor.shutdown(); unavailable = "Google Cast no está disponible en este móvil.";
        }
    }

    public void setDiscovering(boolean active) {
        discovering = active;
        if (router == null) return;
        router.removeCallback(routeCallback);
        router.addCallback(selector, routeCallback, active ? MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN : 0);
        refresh();
    }

    private void refresh() {
        routes.clear();
        if (router != null) for (MediaRouter.RouteInfo route : router.getRoutes()) {
            if (route.isDefault() || !route.isEnabled() || !route.matchesSelector(selector)) continue;
            CastDevice device = CastDevice.getFromBundle(route.getExtras());
            if (device != null && device.hasCapability(CastDevice.CAPABILITY_VIDEO_OUT)) routes.add(route);
        }
        listener.changed();
    }

    public void connect(MediaRouter.RouteInfo route) { route.select(); }
    public boolean connected() { return cast != null && cast.getSessionManager().getCurrentCastSession() != null && cast.getSessionManager().getCurrentCastSession().isConnected(); }
    private RemoteMediaClient client() { return connected() ? cast.getSessionManager().getCurrentCastSession().getRemoteMediaClient() : null; }

    public void load(MediaFile media, String url, boolean loop, Consumer<String> done) {
        RemoteMediaClient client = client();
        if (client == null) { done.accept("Conecta primero con la TV Cast y espera a que termine."); return; }
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, media.name);
        MediaInfo info = new MediaInfo.Builder(url).setContentType(media.mime).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setStreamDuration(media.durationMs).setMetadata(metadata).build();
        MediaQueueItem item = new MediaQueueItem.Builder(info).setAutoplay(true).build();
        client.queueLoad(new MediaQueueItem[]{item}, 0,
                loop ? MediaStatus.REPEAT_MODE_REPEAT_SINGLE : MediaStatus.REPEAT_MODE_REPEAT_OFF, 0, null)
                .setResultCallback(result -> done.accept(result.getStatus().isSuccess() ? null : "Cast rechazó el envío (código " + result.getStatus().getStatusCode() + "). Comprueba el formato y la red."), 30, TimeUnit.SECONDS);
    }

    public void command(boolean pause, Consumer<String> done) {
        RemoteMediaClient client = client();
        if (client == null) { done.accept("Se ha perdido la conexión Cast."); return; }
        (pause ? client.pause() : client.play()).setResultCallback(r -> done.accept(r.getStatus().isSuccess() ? null : "La TV no aceptó el control Cast."), 15, TimeUnit.SECONDS);
    }
    public void repeat(boolean repeat) {
        RemoteMediaClient client = client();
        if (client != null) client.queueSetRepeatMode(repeat ? MediaStatus.REPEAT_MODE_REPEAT_SINGLE : MediaStatus.REPEAT_MODE_REPEAT_OFF, null);
    }
    public void stop() {
        if (cast != null) cast.getSessionManager().endCurrentSession(true);
    }
    private void attach(CastSession session) {
        if (observed != null) observed.unregisterCallback(mediaCallback);
        observed = session.getRemoteMediaClient();
        if (observed != null) observed.registerCallback(mediaCallback);
        listener.session(true, session.getCastDevice() == null ? "TV Cast" : session.getCastDevice().getFriendlyName());
    }
    private void detach(String reason) {
        if (observed != null) observed.unregisterCallback(mediaCallback);
        observed = null;
        listener.session(false, reason);
    }
    private final SessionManagerListener<CastSession> sessions = new SessionManagerListener<>() {
        @Override public void onSessionStarting(CastSession s) { listener.status("Conectando con la TV Cast…"); }
        @Override public void onSessionStarted(CastSession s, String id) { attach(s); }
        @Override public void onSessionStartFailed(CastSession s, int error) { detach("No se pudo conectar con Cast (" + error + ")"); }
        @Override public void onSessionEnding(CastSession s) { }
        @Override public void onSessionEnded(CastSession s, int error) { detach("Sesión Cast cerrada"); }
        @Override public void onSessionResuming(CastSession s, String id) { }
        @Override public void onSessionResumed(CastSession s, boolean suspended) { attach(s); }
        @Override public void onSessionResumeFailed(CastSession s, int error) { detach("No se pudo recuperar Cast (" + error + ")"); }
        @Override public void onSessionSuspended(CastSession s, int reason) { listener.status("Conexión Cast interrumpida. Esperando reconexión…"); }
    };
}
