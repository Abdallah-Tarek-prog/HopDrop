package com.hop.drop.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Joins a PC's direct link (its own Wi-Fi Direct network) when the phone and the PC aren't on the same Wi-Fi.
 *
 * Android 10+: a local-only network request for that exact network. The first time, Android asks "Connect to …?"; it
 * remembers the answer for this app and network, so later joins are silent. Mobile data keeps working for the internet;
 * on phones that can only be on one Wi-Fi at a time, the usual Wi-Fi comes back once the link is released.
 * Android 8–9: the network is added and selected like a normal Wi-Fi (no dialog), and removed again afterwards.
 *
 * The link stays up while something uses it, and is released a little while after the last use ({@link #KEEP_MS}).
 */
public final class DirectLink {
    private static final String TAG = "HopDrop";
    /** How long the link stays after its last use, so a follow-up send doesn't have to join again. */
    public static final long KEEP_MS = 120_000;
    private final Context context;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network network;
    private String ssid;
    private int legacyId = -1;
    private int users;
    private final Runnable release = this::releaseNow;
    /** One join at a time: a second user of the same link waits for it instead of tearing it down. */
    private final Object joining = new Object();
    /** Runs (on a background thread) when the link comes up or goes down, so screens can update. */
    private volatile Runnable changed;

    public DirectLink(Context context) {
        this.context = context.getApplicationContext();
        connectivity = this.context.getSystemService(ConnectivityManager.class);
        wifi = this.context.getSystemService(WifiManager.class);
    }

    public void onChange(Runnable listener) { changed = listener; }

    /** The network name the phone is joined to right now, or null. */
    public synchronized String connectedTo() { return network != null ? ssid : null; }

    /** Identify the interface a connected transfer socket actually used. */
    public boolean owns(InetAddress local) {
        Network active = network;
        LinkProperties properties = active == null ? null : connectivity.getLinkProperties(active);
        if (properties == null) return false;
        for (LinkAddress address : properties.getLinkAddresses())
            if (address.getAddress().equals(local)) return true;
        return false;
    }

    public boolean wifiOn() { return wifi != null && wifi.isWifiEnabled(); }

    /**
     * Joins {@code ssid} (blocking, up to {@code timeoutMs}, which includes the user's answer to Android's question) and
     * marks the link as in use until {@link #done()}. Throws with a plain-words message when it can't.
     */
    public void use(String ssid, String key, String ip, long timeoutMs) throws IOException, InterruptedException {
        synchronized (this) {
            main.removeCallbacks(release);
            users++;
        }
        boolean ok = false;
        try {
            synchronized (joining) {
                synchronized (this) {
                    if (network != null && ssid.equals(this.ssid) && reaches(network, ip)) { ok = true; return; }
                    // Another PC's link is in use: joining this one would cut that transfer off.
                    if (network != null && users > 1) throw new IOException("direct_failed: HopDrop is connected directly to another PC right now. Try again when that's done.");
                }
                if (!wifiOn()) throw new IOException("wifi_off: Turn on Wi-Fi to connect directly");
                drop();
                Network joined = Build.VERSION.SDK_INT >= 29 ? request(ssid, key, timeoutMs) : legacy(ssid, key, ip, timeoutMs);
                synchronized (this) { network = joined; this.ssid = ssid; }
                ok = true;
            }
            notifyChanged();
        } finally {
            if (!ok) done();
        }
    }

    /** Something is moving over the link (a transfer from the PC): keep it a while longer. */
    public synchronized void touch() {
        if (users == 0 && network != null) { main.removeCallbacks(release); main.postDelayed(release, KEEP_MS); }
    }

    /** The user asked to leave the link. */
    public void leave() {
        synchronized (this) { users = 0; main.removeCallbacks(release); }
        drop();
    }

    /** One user of the link is finished; it's released {@link #KEEP_MS} after the last one. */
    public synchronized void done() {
        if (users > 0) users--;
        if (users == 0) { main.removeCallbacks(release); main.postDelayed(release, KEEP_MS); }
    }

    private Network request(String ssid, String key, long timeoutMs) throws IOException, InterruptedException {
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(key).build();
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build();
        CountDownLatch answered = new CountDownLatch(1);
        Network[] found = new Network[1];
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { found[0] = n; answered.countDown(); }
            @Override public void onUnavailable() { answered.countDown(); }
            @Override public void onLost(Network n) {
                synchronized (DirectLink.this) { if (n.equals(network)) network = null; }
                notifyChanged();
            }
        };
        synchronized (this) { callback = cb; }
        try {
            connectivity.requestNetwork(request, cb, (int) Math.min(Integer.MAX_VALUE, timeoutMs));
        } catch (RuntimeException e) {
            synchronized (this) { callback = null; }
            throw new IOException("direct_failed: Android didn't allow HopDrop to connect directly", e);
        }
        if (!answered.await(timeoutMs + 2000, TimeUnit.MILLISECONDS) || found[0] == null) {
            drop();
            throw new IOException("direct_failed: Couldn't connect directly. Check that HopDrop is running on the PC and you're near it.");
        }
        // The address arrives a moment after the network does.
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end && ipv4(found[0]) == null) Thread.sleep(150);
        return found[0];
    }

    @SuppressWarnings("deprecation")
    private Network legacy(String ssid, String key, String ip, long timeoutMs) throws IOException, InterruptedException {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        config.preSharedKey = "\"" + key + "\"";
        int id = wifi.addNetwork(config);
        if (id < 0) throw new IOException("direct_failed: Couldn't add the PC's network");
        synchronized (this) { legacyId = id; }
        wifi.disconnect();
        wifi.enableNetwork(id, true);
        wifi.reconnect();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            for (Network n : connectivity.getAllNetworks()) if (reaches(n, ip)) return n;
            Thread.sleep(300);
        }
        drop();
        throw new IOException("direct_failed: Couldn't connect directly. Check that HopDrop is running on the PC and you're near it.");
    }

    /** Whether {@code n} is a Wi-Fi network whose subnet holds {@code ip}. */
    private boolean reaches(Network n, String ip) {
        try {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(n);
            LinkProperties props = connectivity.getLinkProperties(n);
            if (caps == null || props == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false;
            byte[] target = java.net.InetAddress.getByName(ip).getAddress();
            for (LinkAddress a : props.getLinkAddresses()) {
                if (!(a.getAddress() instanceof Inet4Address)) continue;
                byte[] local = a.getAddress().getAddress();
                int bits = a.getPrefixLength();
                boolean same = true;
                for (int i = 0; i < 4 && same; i++) {
                    int n2 = Math.min(8, bits - i * 8);
                    if (n2 <= 0) break;
                    int mask = (255 << (8 - n2)) & 255;
                    same = ((target[i] ^ local[i]) & mask) == 0;
                }
                if (same) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String ipv4(Network n) {
        LinkProperties props = connectivity.getLinkProperties(n);
        if (props == null) return null;
        for (LinkAddress a : props.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        return null;
    }

    /** The delayed release: only if nobody started using the link again meanwhile. */
    private void releaseNow() {
        synchronized (this) { if (users > 0) return; }
        drop();
    }

    @SuppressWarnings("deprecation")
    private void drop() {
        ConnectivityManager.NetworkCallback cb;
        int id;
        boolean was;
        synchronized (this) {
            cb = callback; callback = null; id = legacyId; legacyId = -1; was = network != null; network = null; ssid = null;
        }
        if (cb != null) try { connectivity.unregisterNetworkCallback(cb); } catch (RuntimeException e) { Log.w(TAG, "Direct link release", e); }
        if (id >= 0) { wifi.removeNetwork(id); wifi.reconnect(); }
        if (was) notifyChanged();
    }

    private void notifyChanged() {
        Runnable listener = changed;
        if (listener != null) listener.run();
    }
}
