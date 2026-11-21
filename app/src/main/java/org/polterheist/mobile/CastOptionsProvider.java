package org.polterheist.mobile;

import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.*;
import java.util.List;

public final class CastOptionsProvider implements OptionsProvider {
    @Override public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                .setStopReceiverApplicationWhenEndingSession(true)
                .setResumeSavedSession(false)
                .build();
    }
    @Override public List<SessionProvider> getAdditionalSessionProviders(Context context) { return null; }
}
