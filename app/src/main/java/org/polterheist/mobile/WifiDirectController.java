package org.polterheist.mobile;

import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.os.Build;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import java.net.InetAddress;
import java.util.*;

/**
 * Wi-Fi Direct (P2P): finds TVs without a shared router and forms a direct group with one.
 * Once the group exists, the TV is reachable over the p2p interface (192.168.49.x) and the regular
 * DLNA / browser paths are reused on that interface.
 *
 * Every call is guarded by {@link NearbyPermissions#wifi(Context)}; lint's MissingPermission is suppressed for that reason.
 */
@SuppressLint("MissingPermission")
public final class WifiDirectController {
    public interface Listener {
        void peers(List<WifiP2pDevice> peers);
        /** Group formed. {@code groupOwner} is the GO IP; if {@code phoneIsOwner} the TV has another 192.168.49.x address. */
        void connected(InetAddress groupOwner, boolean phoneIsOwner, String peerName);
        void disconnected();
        void status(String text);
    }

    private final Context context;
    private final Listener listener;
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private boolean registered, groupFormed, discovering;
    public String connectedName = "";
    private String pendingName = "";
    public final List<WifiP2pDevice> peers = new ArrayList<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) requestPeers();
            else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) onConnectionChanged(intent);
            else if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) listener.status("Wi-Fi Direct desactivado. Activa el Wi-Fi del móvil.");
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                discovering = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
            }
        }
    };

    public WifiDirectController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        boolean supported = this.context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
        manager = supported ? this.context.getSystemService(WifiP2pManager.class) : null;
    }

    public boolean available() { return manager != null; }
    public boolean connected() { return groupFormed; }
    public boolean discovering() { return discovering; }

    private boolean ready() {
        if (manager == null) { listener.status("Este móvil no admite Wi-Fi Direct."); return false; }
        if (!NearbyPermissions.wifi(context)) { listener.status("Falta el permiso de dispositivos Wi-Fi cercanos para Wi-Fi Direct."); return false; }
        if (channel == null) channel = manager.initialize(context, Looper.getMainLooper(), () -> { channel = null; listener.status("Wi-Fi Direct se reinició. Vuelve a buscar."); });
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        }
        return channel != null;
    }

    public void discover() {
        if (!ready()) return;
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { discovering = true; }
            @Override public void onFailure(int reason) {
                listener.status("Wi-Fi Direct no pudo buscar (" + reasonText(reason) + ")." +
                        (Build.VERSION.SDK_INT < 33 ? " Comprueba que la ubicación del móvil esté activada." : ""));
            }
        });
    }
    public void stopDiscovery() {
        if (manager == null || channel == null || !NearbyPermissions.wifi(context)) return;
        manager.stopPeerDiscovery(channel, null);
        discovering = false;
    }

    public void connect(WifiP2pDevice device) {
        if (!ready()) return;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        config.groupOwnerIntent = 0; // Let the TV be group owner: most TVs insist on it.
        pendingName = device.deviceName;
        listener.status("Solicitando conexión Wi-Fi Direct con " + device.deviceName + ". Acepta en la TV si lo pide…");
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { /* result arrives via CONNECTION_CHANGED */ }
            @Override public void onFailure(int reason) { listener.status("La TV no aceptó Wi-Fi Direct (" + reasonText(reason) + ")."); }
        });
    }

    public void disconnect() {
        if (manager == null || channel == null) return;
        manager.cancelConnect(channel, null);
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { }
            @Override public void onFailure(int reason) { }
        });
    }

    public void release() {
        stopDiscovery();
        if (registered) { try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { } registered = false; }
    }

    private void requestPeers() {
        if (manager == null || channel == null || !NearbyPermissions.wifi(context)) return;
        manager.requestPeers(channel, list -> {
            peers.clear();
            if (list != null) peers.addAll(list.getDeviceList());
            listener.peers(new ArrayList<>(peers));
        });
    }

    @SuppressWarnings("deprecation") // NetworkInfo extra is still the documented signal up to API 35.
    private void onConnectionChanged(Intent intent) {
        if (manager == null || channel == null) return;
        NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        boolean up = info != null && info.isConnected();
        if (!up) {
            if (groupFormed) { groupFormed = false; connectedName = ""; listener.disconnected(); }
            return;
        }
        manager.requestConnectionInfo(channel, connection -> {
            if (connection == null || !connection.groupFormed || connection.groupOwnerAddress == null) return;
            boolean wasFormed = groupFormed;
            groupFormed = true;
            WifiP2pGroup group = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
            String name = pendingName;
            if (group != null) {
                if (!connection.isGroupOwner && group.getOwner() != null) name = group.getOwner().deviceName;
                else if (!group.getClientList().isEmpty()) name = group.getClientList().iterator().next().deviceName;
            }
            connectedName = name == null || name.isEmpty() ? "TV Wi-Fi Direct" : name;
            if (!wasFormed) listener.connected(connection.groupOwnerAddress, connection.isGroupOwner, connectedName);
        });
    }

    /** True when the peer advertises itself as a display / multimedia device or as a Miracast sink. */
    public static boolean looksLikeTv(WifiP2pDevice device) {
        String type = device.primaryDeviceType == null ? "" : device.primaryDeviceType;
        // WPS primary device type "category-OUI-subcategory": 7 = Displays, 8 = Multimedia devices.
        if (type.startsWith("7-") || type.startsWith("8-")) return true;
        if (Build.VERSION.SDK_INT >= 30 && device.getWfdInfo() != null && device.getWfdInfo().isEnabled()) {
            int t = device.getWfdInfo().getDeviceType();
            if (t == WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK || t == WifiP2pWfdInfo.DEVICE_TYPE_SOURCE_OR_PRIMARY_SINK) return true;
        }
        return BluetoothTvDiscovery.nameLooksLikeTv(device.deviceName);
    }
    public static String describe(WifiP2pDevice device) {
        String status;
        switch (device.status) {
            case WifiP2pDevice.CONNECTED: status = "conectada"; break;
            case WifiP2pDevice.INVITED: status = "invitación enviada"; break;
            case WifiP2pDevice.FAILED: status = "falló"; break;
            case WifiP2pDevice.UNAVAILABLE: status = "no disponible"; break;
            default: status = "disponible";
        }
        boolean miracast = Build.VERSION.SDK_INT >= 30 && device.getWfdInfo() != null && device.getWfdInfo().isEnabled();
        return status + (miracast ? " · Miracast" : "");
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "no compatible";
            case WifiP2pManager.BUSY: return "ocupado";
            default: return "error interno";
        }
    }
}
