package org.polterheist.mobile;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.*;
import android.os.*;

/** User-started playback, system transport controls, and a visible foreground notification. */
public final class AudioPlaybackService extends Service {
    public static final String PLAY = "org.polterheist.mobile.audio.PLAY";
    private static final String PAUSE = "org.polterheist.mobile.audio.PAUSE", STOP = "org.polterheist.mobile.audio.STOP";
    private static final String CHANNEL = "bluetooth-audio";
    private static final int NOTIFICATION = 25;
    private AudioController audio;
    private MediaSession session;
    private boolean finishing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable idleTimeout = this::finishPlayback;

    @Override public void onCreate() {
        super.onCreate();
        audio = ((PolterApp)getApplication()).audio();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL,"Audio Bluetooth",NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this,"POLTERHEIST audio");
        session.setSessionActivity(openIntent());
        session.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { audio.play(); }
            @Override public void onPause() { audio.pause("Audio en pausa."); }
            @Override public void onStop() { audio.stop(); }
            @Override public void onSeekTo(long position) { audio.seek(position); }
            @Override public void onFastForward() { audio.seek(audio.position()+10_000); }
            @Override public void onRewind() { audio.seek(audio.position()-10_000); }
        },main);
        session.setActive(true); audio.attach(this); promote();
    }
    private PendingIntent openIntent() {
        Intent open = new Intent(this,BluetoothAudioActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this,20,open,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent action(String action, int id) {
        return PendingIntent.getForegroundService(this,id,new Intent(this,AudioPlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        boolean active = audio.playing || audio.preparing;
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_ghost)
                .setContentTitle(audio.file == null ? "POLTERHEIST · Audio" : audio.file.name)
                .setContentText(audio.status).setContentIntent(openIntent()).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(active)
                .setDeleteIntent(action(STOP,23))
                .addAction(new Notification.Action.Builder(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        active ? "Pausar" : "Reproducir",action(active ? PAUSE : PLAY,21)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Detener",action(STOP,22)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1)).build();
    }
    private void promote() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION,notification());
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        finishing = false; main.removeCallbacks(idleTimeout);
        String command = intent == null ? null : intent.getAction();
        if (PLAY.equals(command)) { promote(); audio.playFromService(); }
        else if (PAUSE.equals(command)) audio.pause("Audio en pausa.");
        else audio.stop();
        return START_NOT_STICKY;
    }
    void update() {
        if (finishing || session == null) return;
        int state = audio.preparing ? PlaybackState.STATE_BUFFERING : audio.playing ? PlaybackState.STATE_PLAYING
                : audio.canSeek() ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP;
        if (audio.canSeek()) actions |= PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state,audio.position(),audio.playing ? 1f : 0f).build());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,audio.file == null ? "Audio" : audio.file.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST,audio.selectedName).putLong(MediaMetadata.METADATA_KEY_DURATION,audio.duration()).build());
        getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
    }
    void idle() {
        stopForeground(STOP_FOREGROUND_DETACH);
        main.removeCallbacks(idleTimeout); main.postDelayed(idleTimeout,5*60_000);
    }
    void finishPlayback() {
        finishing = true; main.removeCallbacks(idleTimeout);
        stopForeground(STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION); stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!audio.playing && !audio.preparing) finishPlayback();
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        main.removeCallbacks(idleTimeout); finishing = true;
        audio.detached(this);
        if (session != null) { session.setActive(false); session.release(); session = null; }
        getSystemService(NotificationManager.class).cancel(NOTIFICATION);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
