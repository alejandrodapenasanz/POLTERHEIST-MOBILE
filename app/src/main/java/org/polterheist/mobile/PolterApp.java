package org.polterheist.mobile;

import android.app.Application;

public final class PolterApp extends Application {
    public AppController controller;
    @Override public void onCreate() {
        super.onCreate();
        controller = new AppController(this);
    }
}
