package org.polterheist.mobile;

import android.app.Application;

public final class PolterApp extends Application {
    public AppController controller;
    private BluetoothDiscovery bluetoothDiscovery;
    private AudioController audioController;

    public BluetoothDiscovery bluetooth() {
        if (bluetoothDiscovery == null) bluetoothDiscovery = new BluetoothDiscovery(this);
        return bluetoothDiscovery;
    }

    public AudioController audio() {
        if (audioController == null) audioController = new AudioController(this);
        return audioController;
    }

    public boolean audioActive() {
        return audioController != null && (audioController.playing || audioController.preparing);
    }
    @Override public void onCreate() {
        super.onCreate();
        controller = new AppController(this);
    }
}
