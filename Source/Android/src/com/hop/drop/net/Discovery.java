package com.hop.drop.net;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * UDP discovery (spec 3.8). Every send is attempted on its own: one interface that refuses a packet
 * (common on phones that are on Wi-Fi and hosting a hotspot at once) must never stop the others,
 * and must never stop discovery from starting.
 *
 * Battery: while HopDrop is on screen it holds a multicast lock (the Wi-Fi chip then passes all group traffic to the
 * phone) and announces every 5 s. In the background it lets the chip filter that traffic and announces every 20 s;
 * paired devices still reach the phone at its saved address, and the phone answers as soon as HopDrop opens.
 */
public final class Discovery implements AutoCloseable {
    private static final String TAG = "HopDrop";
    private static final String SKIP = "(?i).*(rmnet|ccmni|pdp|tun|tap|vpn|dummy).*";
    /** A device that hasn't announced itself for this long is no longer shown as nearby. */
    private static final long EXPIRY_MS = 45000;
    private static final long FOREGROUND_MS = 5000, BACKGROUND_MS = 20000, INTERFACES_MS = 120000;

    public static final class Nearby {
        /** {@code name} is empty when the device hides its name from devices it isn't paired with. */
        public final String id, name, platform, address;
        public final int port;
        public final long seen;

        Nearby(String id, String name, String platform, String address, int port) {
            this.id = id;
            this.name = name;
            this.platform = platform;
            this.address = address;
            this.port = port;
            seen = System.currentTimeMillis();
        }
    }

    private final String id;
    private final Supplier<String> name;
    private final WifiManager.MulticastLock lock;
    private final Map<String, Nearby> nearby = new ConcurrentHashMap<>();
    private final Set<String> reported = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Runnable changed;
    private final Object wake = new Object();
    private final AtomicBoolean closing = new AtomicBoolean(), cleaned = new AtomicBoolean();
    private volatile boolean active, foreground, hidden;
    private volatile List<NetworkInterface> interfaces;
    private volatile long interfacesAt;
    private volatile MulticastSocket socket;

    /** {@code changed} runs on a background thread whenever the nearby list changes. */
    public Discovery(Context context, Peer peer, Runnable changed) {
        this(context, peer.id(), peer::name, changed);
    }

    Discovery(Context context, String id, Runnable changed) {
        this(context, id, () -> "", changed);
    }

    private Discovery(Context context, String id, Supplier<String> name, Runnable changed) {
        this.id = id;
        this.name = name;
        this.changed = changed;
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        lock = wifi.createMulticastLock("HopDrop discovery");
        lock.setReferenceCounted(false);
    }

    public List<Nearby> list() {
        expire();
        return new ArrayList<>(nearby.values());
    }

    public boolean isActive() {
        return active;
    }

    public void start() throws Exception {
        if (closing.get()) throw new IllegalStateException("Discovery is closed");
        if (active) return;
        MulticastSocket created = new MulticastSocket(7410);
        try {
            created.setBroadcast(true);
            created.setTimeToLive(1);
        } catch (Exception error) {
            created.close();
            throw error;
        }
        socket = created;
        joinLocalInterfaces();
        active = true;
        if (foreground) lock.acquire();
        Thread reader = new Thread(this::readLoop, "HopDrop discovery read");
        reader.setDaemon(true);
        reader.start();
        Thread announcer = new Thread(this::announceLoop, "HopDrop discovery announce");
        announcer.setDaemon(true);
        announcer.start();
        query();
    }

    /** HopDrop is on screen (true) or not (false): switches between the quick, chatty mode and the battery-saving one. */
    public void setForeground(boolean value) {
        if (foreground == value) return;
        foreground = value;
        if (!active) return;
        if (value) {
            lock.acquire();
            query();
        } else if (lock.isHeld()) {
            lock.release();
        }
        wakeAnnouncer();
    }

    /** Hidden: announcements carry no name, so only paired devices (which know the id) recognise this phone. */
    public void setHidden(boolean value) {
        if (hidden == value) return;
        hidden = value;
        announce();
    }

    public void query() {
        if (active) new Thread(() -> broadcast("query"), "HopDrop discovery query").start();
    }

    public void announce() {
        if (!active) return;
        new Thread(() -> {
            joinLocalInterfaces();
            broadcast("announce");
        }, "HopDrop discovery announce now").start();
    }

    /** The phone joined or left a network: read its interfaces again and tell the new network it's here. */
    public void networkChanged() {
        interfaces = null;
        announce();
    }

    private void readLoop() {
        byte[] buffer = new byte[1500];
        while (active) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() > 1200) continue;
                handle(new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        java.nio.charset.StandardCharsets.UTF_8), packet.getAddress());
            } catch (Exception error) {
                if (!active) return;
            }
        }
    }

    void handle(String json, InetAddress from) {
        Map<String, Object> m = Json.parseObject(json);
        if (Json.num(m, "hopdrop") != 2) return;
        String id = Json.str(m, "id");
        if (id.equalsIgnoreCase(this.id)) return;
        String type = Json.str(m, "type");
        if (type.equals("bye")) {
            if (nearby.remove(id) != null) changed.run();
            return;
        }
        if (!type.equals("query") && !type.equals("announce")) return;
        String address = from.getHostAddress();
        Nearby next = new Nearby(id, Json.str(m, "name"), Json.str(m, "pl"), address, (int) Json.num(m, "p"));
        Nearby prior = nearby.put(id, next);
        if (socket != null && (type.equals("query") || prior == null)) sendTo("announce", from);
        if (prior == null || !prior.name.equals(next.name) || !prior.address.equals(next.address)) changed.run();
    }

    /** Runs on its own thread: Android forbids network calls on the main thread, where start() is called. */
    private void announceLoop() {
        while (active) {
            broadcast("announce");
            expire();
            synchronized (wake) {
                try {
                    wake.wait(foreground ? FOREGROUND_MS : BACKGROUND_MS);
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }
    }

    private void wakeAnnouncer() {
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        if (nearby.values().removeIf(n -> now - n.seen > EXPIRY_MS)) changed.run();
    }

    private void joinLocalInterfaces() {
        for (NetworkInterface ni : interfaces()) {
            try {
                socket.joinGroup(new InetSocketAddress("239.255.74.10", 7410), ni);
            } catch (Exception ignored) {
                // Already joined, or this interface has no multicast.
            }
        }
    }

    /** This phone's usable interfaces, read once per network change (or every two minutes) instead of on every announcement. */
    private List<NetworkInterface> interfaces() {
        List<NetworkInterface> cached = interfaces;
        if (cached != null && SystemClock.elapsedRealtime() - interfacesAt < INTERFACES_MS) return cached;
        List<NetworkInterface> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isUp() && !ni.isLoopback() && !ni.getName().matches(SKIP)) result.add(ni);
            }
        } catch (Exception error) {
            warn("list interfaces", error);
        }
        interfacesAt = SystemClock.elapsedRealtime();
        interfaces = result;
        return result;
    }

    private byte[] message(String type) {
        return Json.string(Json.obj("hopdrop", 2, "type", type, "id", id, "name", type.equals("bye") || hidden ? "" : name.get(),
                "pl", "android", "p", 7410, "rx", !type.equals("bye"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void sendTo(String type, InetAddress destination) {
        try {
            MulticastSocket current = socket;
            if (current == null) return;
            byte[] data = message(type);
            if (data.length <= 1200) current.send(new DatagramPacket(data, data.length, destination, 7410));
        } catch (Exception error) {
            warn("send " + type + " to " + destination.getHostAddress(), error);
        }
    }

    private synchronized void broadcast(String type) {
        if (!active && socket == null) return;
        sendTo(type, broadcastAll());
        for (NetworkInterface ni : interfaces()) {
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (!(ia.getAddress() instanceof Inet4Address) || ia.getBroadcast() == null) continue;
                try {
                    socket.setNetworkInterface(ni);
                    sendTo(type, InetAddress.getByName("239.255.74.10"));
                } catch (Exception error) {
                    warn("multicast on " + ni.getName(), error);
                }
                sendTo(type, ia.getBroadcast());
            }
        }
    }

    private static InetAddress broadcastAll() {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Logs each distinct failure once, so a refused interface doesn't flood the log. */
    private void warn(String what, Exception error) {
        String key = what + ": " + error;
        if (reported.add(key)) Log.w(TAG, "Discovery " + key);
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        active = false;
        wakeAnnouncer();
        MulticastSocket current = socket;
        Thread goodbye = new Thread(() -> {
            try {
                if (current != null) {
                    broadcast("bye");
                    Thread.sleep(100);
                    broadcast("bye");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                finishClose(current);
            }
        }, "HopDrop discovery goodbye");
        goodbye.setDaemon(true);
        goodbye.start();
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(300); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            finishClose(current);
        }, "HopDrop discovery close");
        deadline.setDaemon(true);
        deadline.start();
    }

    private void finishClose(MulticastSocket current) {
        if (!cleaned.compareAndSet(false, true)) return;
        socket = null;
        if (current != null) current.close();
        if (lock.isHeld()) lock.release();
    }
}
