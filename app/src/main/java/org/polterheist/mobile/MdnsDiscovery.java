package org.polterheist.mobile;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.*;

/**
 * mDNS / DNS-SD discovery for TVs that do not answer SSDP (or answer it only to some clients).
 * Found hosts are then probed for DLNA by IP; if none is offered they are listed as "detected".
 * Runs on every local path Android's NsdManager uses (normally Wi-Fi/Ethernet; hotspot on some devices).
 */
public final class MdnsDiscovery {
    public interface Listener { void found(String name, String type, InetAddress host); }

    /** Service types announced by common TVs/sticks. Extend here if a model announces something else. */
    static final String[] TYPES = {
            "_googlecast._tcp.",       // Chromecast / Google TV / Android TV with Cast
            "_androidtvremote2._tcp.", // Android TV / Google TV remote service
            "_airplay._tcp.",          // AirPlay receivers (Samsung, LG, Sony, Vizio, Roku 2019+)
            "_amzn-wplay._tcp."        // Fire TV
    };
    static String label(String type) {
        if (type.startsWith("_googlecast")) return "Google Cast";
        if (type.startsWith("_androidtvremote")) return "Android TV";
        if (type.startsWith("_airplay")) return "AirPlay";
        if (type.startsWith("_amzn")) return "Fire TV";
        return "mDNS";
    }

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<NsdManager.DiscoveryListener> active = new ArrayList<>();
    private final Deque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving, running;
    private Listener listener;
    private final Runnable timeout = this::stop;

    public MdnsDiscovery(Context context) {
        NsdManager manager;
        try { manager = context.getApplicationContext().getSystemService(NsdManager.class); }
        catch (RuntimeException e) { manager = null; }
        nsd = manager;
    }

    public void start(Listener listener, long durationMs) {
        stop();
        if (nsd == null) return;
        this.listener = listener;
        running = true;
        for (String type : TYPES) {
            NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int code) { }
                @Override public void onStopDiscoveryFailed(String t, int code) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceFound(NsdServiceInfo info) { main.post(() -> enqueue(info)); }
                @Override public void onServiceLost(NsdServiceInfo info) { }
            };
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery); active.add(discovery); }
            catch (RuntimeException ignored) { }
        }
        main.postDelayed(timeout, durationMs);
    }

    public void stop() {
        main.removeCallbacks(timeout);
        running = false;
        queue.clear();
        for (NsdManager.DiscoveryListener d : active) { try { nsd.stopServiceDiscovery(d); } catch (RuntimeException ignored) { } }
        active.clear();
    }

    private void enqueue(NsdServiceInfo info) {
        if (!running || queue.size() > 64) return;
        queue.add(info);
        next();
    }

    /** NsdManager before API 34 allows a single resolve at a time, so resolves are serialized. */
    @SuppressWarnings("deprecation")
    private void next() {
        if (resolving || queue.isEmpty() || !running) return;
        resolving = true;
        NsdServiceInfo info = queue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int code) { main.post(() -> { resolving = false; next(); }); }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress host = s.getHost();
                    String type = s.getServiceType() == null ? "" : s.getServiceType().replaceFirst("^\\.", "");
                    main.post(() -> {
                        resolving = false;
                        if (running && listener != null && host instanceof Inet4Address) listener.found(friendly(s), type, host);
                        next();
                    });
                }
            });
        } catch (RuntimeException e) { resolving = false; main.post(this::next); }
    }

    /** Uses the TXT "fn" (Cast friendly name) when present, else the service instance name. */
    private static String friendly(NsdServiceInfo s) {
        try {
            byte[] fn = s.getAttributes().get("fn");
            if (fn != null && fn.length > 0) return new String(fn, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) { }
        return s.getServiceName();
    }
}
