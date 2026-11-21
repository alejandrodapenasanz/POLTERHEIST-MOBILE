package org.polterheist.mobile;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import androidx.core.app.NotificationCompat;

public final class ShareService extends Service {
    private AppController controller;
    private PowerManager.WakeLock wake;
    private ConnectivityManager manager;
    private boolean registered;
    private final ConnectivityManager.NetworkCallback networks = new ConnectivityManager.NetworkCallback() {
        @Override public void onLost(Network network) {
            controller.main.post(() -> {
                if (controller.network != null && network.equals(controller.network.network)) controller.networkLost();
            });
        }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            controller.main.post(() -> {
                if (controller.network != null && network.equals(controller.network.network) &&
                    properties.getLinkAddresses().stream().noneMatch(a -> a.getAddress().equals(controller.network.address))) controller.networkLost();
            });
        }
    };
    // User-visible foreground transfer: release in onDestroy; Android releases on process death.
    @android.annotation.SuppressLint("WakelockTimeout")
    @Override public void onCreate() {
        super.onCreate();
        controller = ((PolterApp) getApplication()).controller;
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("sharing", "Envío a la TV", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ShareService.class).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, "sharing").setSmallIcon(R.drawable.ic_ghost)
                .setContentTitle("POLTERHEIST · Envío a la TV")
                .setContentText("El móvil está compartiendo tu archivo en la red local")
                .setContentIntent(open).setOngoing(true).addAction(0, "Detener", stop).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(24, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(24, notification);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "polterheist:sharing");
        wake.acquire();
        manager = getSystemService(ConnectivityManager.class);
        manager.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), networks);
        registered = true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "STOP".equals(intent.getAction())) controller.stop();
        else controller.serviceReady();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if (registered) manager.unregisterNetworkCallback(networks);
        if (wake != null && wake.isHeld()) wake.release();
        controller.serviceDestroyed();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
