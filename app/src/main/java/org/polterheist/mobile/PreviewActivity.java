package org.polterheist.mobile;

import android.graphics.Color;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;

/** Local fullscreen preview also works with the manufacturer's screen mirroring. */
public final class PreviewActivity extends AppCompatActivity {
    private VideoView video;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MediaFile media = ((PolterApp)getApplication()).controller.media;
        if (media == null) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView());
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK); setContentView(root);
        if (media.isImage()) {
            ImageView image = new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageURI(android.net.Uri.fromFile(media.file));
            image.setContentDescription(media.name); root.addView(image,new FrameLayout.LayoutParams(-1,-1));
        } else {
            video = new VideoView(this); root.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
            MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);
            video.setVideoPath(media.file.getPath());
            video.setOnPreparedListener(player->{player.setLooping(((PolterApp)getApplication()).controller.loop);video.start();});
            video.setOnErrorListener((player,what,extra)->{Toast.makeText(this,"El móvil no puede reproducir este vídeo",Toast.LENGTH_LONG).show();return true;});
        }
    }
    @Override protected void onPause(){if(video!=null)video.pause();super.onPause();}
    @Override protected void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
