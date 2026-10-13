package com.hop.drop;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import com.hop.drop.net.DirectLink;
import com.hop.drop.net.Discovery;
import com.hop.drop.net.LocalSockets;
import com.hop.drop.store.DeviceBook;
import com.hop.drop.store.Identity;
import com.hop.drop.store.ReceiveStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class HopApp extends Application {
    public static final class Prompt {
        public final String name;
        public final String code;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean answer;

        Prompt(String name, String code) {
            this.name = name;
            this.code = code;
        }

        public void answer(boolean value) {
            answer = value;
            done.countDown();
        }

        boolean await() throws InterruptedException {
            return done.await(120, TimeUnit.SECONDS) && answer;
        }

        boolean isPending() {
            return done.getCount() > 0;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Peer peer;
    private DeviceBook book;
    private Notices notices;
    private Live live;
    private DirectLink link;
    /** Held while files move: with the screen off, Android otherwise slows Wi-Fi down to save power (several times slower). */
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private android.os.PowerManager.WakeLock wakeLock;
    private final Runnable releaseLocks = this::releaseLocksIfIdle;
    private volatile MainActivity visible;
    private volatile Prompt prompt;
    private volatile long lastUiRefresh;
    private boolean uiRefreshQueued;
    private final Map<String, Peer.Offer> offers = new ConcurrentHashMap<>();
    private volatile boolean pairingVisible;
    /** What is being sent to each device right now: [name, uri] pairs, so Activity can open the files later. */
    private final Map<String, List<Object>> sources = new ConcurrentHashMap<>();

    public Peer peer() {
        return peer;
    }

    public DeviceBook book() {
        return book;
    }

    public Prompt prompt() {
        return prompt;
    }

    Notices notices() {
        return notices;
    }

    Live live() {
        return live;
    }

    /** Joins a PC's own Wi-Fi when the phone isn't on the same network as it. */
    public DirectLink directLink() {
        return link;
    }

    public void visible(MainActivity activity) {
        visible = activity;
        if (activity != null && prompt != null) activity.showPrompt(prompt);
        if (activity != null) for (Peer.Offer offer : offers.values()) activity.showOffer(offer);
        Discovery discovery = ReceiveService.activeDiscovery;
        if (discovery != null) discovery.setForeground(activity != null);
        if (activity == null) setPairingVisible(false);
    }

    boolean isVisible() {
        return visible != null;
    }

    /** The Devices tab is on screen: a phone hidden from unpaired devices shows its name until the tab closes. */
    void setPairingVisible(boolean value) {
        pairingVisible = value;
        Discovery discovery = ReceiveService.activeDiscovery;
        if (discovery != null) discovery.setHidden(hidden());
    }

    /** "Only my paired devices": discovery announcements carry no name. */
    boolean hidden() {
        return "paired".equals(getSharedPreferences("settings_v2", MODE_PRIVATE).getString("visibility", "everyone"))
                && !pairingVisible;
    }

    /** TransferService calls this right before it sends to {@code peer} (it sends one transfer at a time). */
    void sendingFrom(String peer, List<Object> files) {
        sources.put(peer, files);
    }

    void answerOffer(String id, boolean accept) {
        Peer.Offer offer = id == null ? null : offers.get(id);
        if (offer != null) offer.answer(accept);
    }

    /**
     * Discovery's list changed: a device that has sends waiting for it, or the backup's device, may be back. Only devices
     * on the same network are tried here; joining a direct link waits until HopDrop is on screen.
     */
    void nearbyChanged() {
        changed();
        Discovery discovery = ReceiveService.activeDiscovery;
        if (discovery == null) return;
        for (Discovery.Nearby n : discovery.list()) {
            if (Sends.waitingFor(this, n.id) > 0) Sends.retry(this, n.id, false);
            Backup.deviceSeen(this, n.id);
        }
    }

    /** Redraws the visible screen soon, at most about four times a second. */
    void changed() {
        synchronized (main) {
            if (uiRefreshQueued) return;
            uiRefreshQueued = true;
        }
        long wait = Math.max(0, 250 - (SystemClock.elapsedRealtime() - lastUiRefresh));
        main.postDelayed(() -> {
            synchronized (main) {
                uiRefreshQueued = false;
            }
            lastUiRefresh = SystemClock.elapsedRealtime();
            MainActivity activity = visible;
            if (activity != null) activity.onLiveChanged();
        }, wait);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("transfers", "Transfers in progress",
                NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("results", "Finished transfers",
                NotificationManager.IMPORTANCE_DEFAULT));
        // Pops up on screen (heads-up) when files arrive, also while HopDrop is in the background.
        manager.createNotificationChannel(new NotificationChannel("received", "Files received",
                NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(new NotificationChannel("pairing", "Pairing requests",
                NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(new NotificationChannel("requests", "Requests to send you files",
                NotificationManager.IMPORTANCE_HIGH));
        // Text a paired device copied and HopDrop put on this phone's clipboard: a quiet note, no pop-up.
        manager.createNotificationChannel(new NotificationChannel("clipboard", "Copied from your devices",
                NotificationManager.IMPORTANCE_LOW));
        book = new DeviceBook(this);
        notices = new Notices(this);
        live = new Live(this::changed);
        link = new DirectLink(this);
        try {
            @SuppressWarnings("deprecation")
            int mode = android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = getSystemService(android.net.wifi.WifiManager.class).createWifiLock(mode, "HopDrop:transfer");
            wifiLock.setReferenceCounted(false);
            wakeLock = getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "HopDrop:transfer");
            wakeLock.setReferenceCounted(false);
        } catch (RuntimeException e) {
            android.util.Log.w("HopDrop", "Transfer locks unavailable", e);
        }
        link.onChange(this::changed);
        try {
            Identity identity = Identity.load();
            SharedPreferences settings = getSharedPreferences("settings_v2", MODE_PRIVATE);
            peer = new Peer(identity.key, identity.cert, settings.getString("name", Identity.defaultName(this)), book,
                    new LocalSockets(this), new ReceiveStorage(this), new Events());
            peer.askBeforeReceiving = settings.getBoolean("ask", false);
        } catch (Exception e) {
            android.util.Log.e("HopDrop", "Identity initialization failed", e);
        }
    }

    private final class Events implements Peer.Events {
        @Override
        public boolean confirm(String name, String code, boolean incoming) throws InterruptedException {
            Prompt p = new Prompt(name, code);
            prompt = p;
            MainActivity activity = visible;
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (activity != null) activity.runOnUiThread(() -> activity.showPrompt(p));
            else manager.notify(Notices.PAIRING, notification("pairing", name + " wants to pair",
                    "Tap to compare the number " + code, false, -1).setPriority(Notification.PRIORITY_HIGH)
                    .setAutoCancel(true).build());
            boolean answer = p.await();
            if (p.isPending()) {
                p.answer(false);
                MainActivity showing = visible;
                if (showing != null) showing.runOnUiThread(() -> showing.closePrompt(p, "Pairing timed out."));
            }
            prompt = null;
            manager.cancel(Notices.PAIRING);
            return answer;
        }

        @Override
        public void progress(Peer.Progress p) {
            link.touch();
            holdLocks();
            live.progress(p);
            notices.progress(p);
        }

        @Override
        public void finished(Peer.Result r) {
            main.removeCallbacks(releaseLocks);
            main.postDelayed(releaseLocks, 3000);
            live.finished(r);
            notices.result(r);
            saveHistory(r);
            MainActivity activity = visible;
            if (activity != null) activity.runOnUiThread(activity::refresh);
        }

        @Override
        public void paired(Peer.Device d) {
            getSystemService(NotificationManager.class).notify(Notices.PAIRING + 100, notification("results",
                    "Paired with " + d.name, "You can now send files both ways. No codes needed next time.",
                    false, -1).setAutoCancel(true).build());
            MainActivity activity = visible;
            if (activity != null) activity.runOnUiThread(activity::onPaired);
        }

        @Override
        public boolean offer(Peer.Offer offer) {
            offers.put(offer.transferId, offer);
            notices.offer(offer);
            MainActivity activity = visible;
            if (activity != null) activity.runOnUiThread(() -> activity.showOffer(offer));
            return true;
        }

        @Override
        public void offerClosed(Peer.Offer offer) {
            offers.remove(offer.transferId);
            notices.clearOffer(offer);
            MainActivity activity = visible;
            if (activity != null) activity.runOnUiThread(() -> activity.closeOffer(offer));
        }

        @Override
        public void text(Peer.Device from, String text, boolean clip) {
            String name = from == null ? "A paired device" : from.label();
            boolean copied = clip && getSharedPreferences("settings_v2", MODE_PRIVATE).getBoolean("clip_auto", true) && copyText(text);
            notices.text(name, text, copied);
            saveText(true, name, text);
            MainActivity activity = visible;
            if (activity != null) activity.runOnUiThread(activity::refresh);
        }

        @Override
        public void contacted(Peer.Device d) {
            if (Sends.waitingFor(HopApp.this, d.id) > 0) Sends.retry(HopApp.this, d.id, false);
            Backup.deviceSeen(HopApp.this, d.id);
        }

        @Override
        public void pairingEnded(String reason) {
            Prompt current = prompt;
            if (current == null || !current.isPending()) return;
            current.answer(false);
            MainActivity activity = visible;
            if (activity != null) activity.runOnUiThread(() -> activity.closePrompt(current, reason));
        }
    }

    public Notification.Builder notification(String channel, String title, String detail, boolean ongoing, int percent) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_notification)
                .setColor(getColor(R.color.brand)).setContentTitle(title).setContentText(detail)
                .setContentIntent(open).setOnlyAlertOnce(ongoing).setOngoing(ongoing);
        if (percent >= 0) b.setProgress(100, percent, false);
        else if (ongoing && channel.equals("transfers") && !title.startsWith("Ready")) b.setProgress(0, 0, true);
        return b;
    }

    /** A transfer is moving: full-speed Wi-Fi and an awake processor until shortly after the last one ends (at most an hour). */
    private void holdLocks() {
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L);
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseLocksIfIdle() {
        if (live.busy()) {
            main.postDelayed(releaseLocks, 3000);
            return;
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** Puts text on the clipboard (on the main thread). False when Android didn't allow it. */
    boolean copyText(String text) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("HopDrop", text));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        main.post(() -> {
            try {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(android.content.ClipData.newPlainText("HopDrop", text));
                ok[0] = true;
            } catch (RuntimeException ignored) {
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    /** Records a text message in Activity. */
    @SuppressWarnings("unchecked")
    synchronized void saveText(boolean incoming, String peer, String text) {
        SharedPreferences p = getSharedPreferences("history_v2", MODE_PRIVATE);
        try {
            List<Object> list = (List<Object>) Json.parseObject(p.getString("data", "{\"items\":[]}")).get("items");
            list.add(0, Json.obj("at", System.currentTimeMillis(), "incoming", incoming, "peer", peer, "files", new ArrayList<>(),
                    "count", 0, "text", text, "bytes", (long) text.length(), "offered", 0, "millis", 0));
            while (list.size() > 100) list.remove(list.size() - 1);
            p.edit().putString("data", Json.string(Json.obj("items", list))).apply();
        } catch (Exception ignored) {
        }
    }

    /** Where a received file ended up ("name" or "Photos/2024/name"), so notifications and Activity can open it. */
    public Uri receivedUri(String path) {
        try {
            int slash = path.lastIndexOf('/');
            String folder = slash < 0 ? null : path.substring(0, slash), name = path.substring(slash + 1);
            String saved = getSharedPreferences("settings_v2", MODE_PRIVATE).getString("folder", null);
            if (saved != null) {
                Uri tree = Uri.parse(saved);
                String parent = DocumentsContract.getTreeDocumentId(tree);
                String[] steps = folder == null ? new String[0] : folder.split("/");
                for (int step = 0; step <= steps.length; step++) {
                    String wanted = step < steps.length ? steps[step] : name;
                    String found = null;
                    try (android.database.Cursor c = getContentResolver().query(
                            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                        if (c != null) while (c.moveToNext()) {
                            if (wanted.equals(c.getString(1))) {
                                found = c.getString(0);
                                break;
                            }
                        }
                    }
                    if (found == null) return null;
                    if (step == steps.length) return DocumentsContract.buildDocumentUriUsingTree(tree, found);
                    parent = found;
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                try (android.database.Cursor c = getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID}, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                                + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{folder == null ? "Download/HopDrop/" : "Download/HopDrop/" + folder + "/", name},
                        null)) {
                    if (c != null && c.moveToFirst()) {
                        return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                c.getLong(0));
                    }
                }
            } else {
                Uri.Builder legacy = new Uri.Builder().scheme("content").authority(getPackageName() + ".sharedtext")
                        .appendPath("received");
                for (String segment : path.split("/")) legacy.appendPath(segment);
                return legacy.build();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private synchronized void saveHistory(Peer.Result r) {
        SharedPreferences p = getSharedPreferences("history_v2", MODE_PRIVATE);
        try {
            List<Object> list = (List<Object>) Json.parseObject(p.getString("data", "{\"items\":[]}")).get("items");
            // A received folder can hold thousands of files: keep the first 1,000 names and look up where the first
            // 50 were saved (Activity finds any other one when it's tapped), so history stays small and quick.
            List<String> names = r.files.size() > 1000 ? new ArrayList<>(r.files.subList(0, 1000)) : r.files;
            List<Object> uris = new ArrayList<>();
            if (r.incoming) for (int i = 0; i < Math.min(50, names.size()); i++) {
                Uri uri = receivedUri(names.get(i));
                uris.add(uri == null ? null : uri.toString());
            }
            Map<String, Object> entry = Json.obj("at", System.currentTimeMillis(), "incoming", r.incoming, "peer", r.peer,
                    "files", names, "count", r.files.size(), "uris", uris, "folder", r.folder, "error", r.error,
                    "bytes", r.bytes, "offered", r.offered, "millis", r.millis);
            List<Object> sent = r.incoming ? null : sources.remove(r.peer);
            if (sent != null) entry.put("sources", sent);
            list.add(0, entry);
            while (list.size() > 100) list.remove(list.size() - 1);
            p.edit().putString("data", Json.string(Json.obj("items", list))).apply();
        } catch (Exception ignored) {
        }
    }

    /** Removes one transfer from Activity (the files themselves stay). */
    @SuppressWarnings("unchecked")
    public synchronized void removeHistory(long at, boolean incoming, String peer) {
        SharedPreferences p = getSharedPreferences("history_v2", MODE_PRIVATE);
        try {
            List<Object> list = (List<Object>) Json.parseObject(p.getString("data", "{\"items\":[]}")).get("items");
            list.removeIf(item -> {
                Map<String, Object> e = (Map<String, Object>) item;
                return e.get("at") instanceof Number && ((Number) e.get("at")).longValue() == at
                        && Boolean.valueOf(incoming).equals(e.get("incoming")) && peer.equals(String.valueOf(e.get("peer")));
            });
            p.edit().putString("data", Json.string(Json.obj("items", list))).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized void clearHistory() {
        getSharedPreferences("history_v2", MODE_PRIVATE).edit().remove("data").apply();
    }
}
