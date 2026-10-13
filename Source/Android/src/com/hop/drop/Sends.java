package com.hop.drop;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;
import com.hop.drop.core.Format;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
import com.hop.drop.net.Discovery;
import com.hop.drop.net.LocalSockets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the phone sends: what a send is ({@link Job}), how a device is reached (the same network first, then its
 * direct link), sends to several devices at once (one queue per device), and "sends when it's back": a send to a device
 * that can't be reached waits in a saved queue and goes out when the device shows up again.
 */
final class Sends {
    /** One send to one device: files, whole folders, explicit files (backups) and text. */
    static final class Job {
        final String id, deviceId;
        /** "3 files", "1 folder and 2 files": what the notifications call it. */
        String summary;
        final List<Uri> files = new ArrayList<>(), trees = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        /** Backups: files listed by the backup itself, each with its place in the backed-up folder. */
        final List<String[]> listed = new ArrayList<>();
        /** The text was copied on this phone (the receiver may put it on its clipboard). */
        boolean clip;
        /** Backups: sent folders fill the receiver's existing folder of the same name. */
        boolean merge;
        boolean backup;
        final long createdAt;

        Job(String id, String deviceId, String summary, long createdAt) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            this.deviceId = deviceId;
            this.summary = summary == null ? "" : summary;
            this.createdAt = createdAt;
        }

        Job(String deviceId, String summary) { this(null, deviceId, summary, System.currentTimeMillis()); }

        boolean textOnly() { return files.isEmpty() && trees.isEmpty() && listed.isEmpty() && !texts.isEmpty(); }

        Intent intent(Context context) {
            Intent intent = new Intent(context, TransferService.class).putExtra("job", Json.string(json()))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            List<Uri> all = new ArrayList<>(files);
            all.addAll(trees);
            for (String[] item : listed) all.add(Uri.parse(item[0]));
            if (!all.isEmpty()) {
                ClipData clipData = ClipData.newRawUri("HopDrop files", all.get(0));
                for (int i = 1; i < all.size(); i++) clipData.addItem(new ClipData.Item(all.get(i)));
                intent.setClipData(clipData);
            }
            return intent;
        }

        Map<String, Object> json() {
            List<Object> f = new ArrayList<>(), t = new ArrayList<>(), l = new ArrayList<>();
            for (Uri u : files) f.add(u.toString());
            for (Uri u : trees) t.add(u.toString());
            for (String[] item : listed) l.add(java.util.Arrays.asList((Object[]) item));
            return Json.obj("id", id, "device", deviceId, "summary", summary, "at", createdAt, "files", f, "trees", t,
                    "texts", new ArrayList<Object>(texts), "listed", l, "clip", clip, "merge", merge, "backup", backup);
        }

        static Job from(Map<String, Object> m) {
            Job job = new Job(Json.str(m, "id"), Json.str(m, "device"), (String) m.get("summary"),
                    m.get("at") instanceof Number ? ((Number) m.get("at")).longValue() : System.currentTimeMillis());
            for (Object o : list(m.get("files"))) job.files.add(Uri.parse(String.valueOf(o)));
            for (Object o : list(m.get("trees"))) job.trees.add(Uri.parse(String.valueOf(o)));
            for (Object o : list(m.get("texts"))) job.texts.add(String.valueOf(o));
            for (Object o : list(m.get("listed"))) {
                List<?> item = list(o);
                if (item.size() == 5) job.listed.add(new String[]{String.valueOf(item.get(0)), String.valueOf(item.get(1)),
                        item.get(2) == null ? null : String.valueOf(item.get(2)), String.valueOf(item.get(3)), String.valueOf(item.get(4))});
            }
            job.clip = Boolean.TRUE.equals(m.get("clip"));
            job.merge = Boolean.TRUE.equals(m.get("merge"));
            job.backup = Boolean.TRUE.equals(m.get("backup"));
            return job;
        }

        private static List<?> list(Object o) { return o instanceof List ? (List<?>) o : new ArrayList<>(); }
    }

    /** A device ready to connect to; {@code viaLink}: through its direct link, which must be handed back with done(). */
    static final class Reached {
        final Peer.Device device;
        final boolean viaLink;
        Reached(Peer.Device device, boolean viaLink) { this.device = device; this.viaLink = viaLink; }
    }

    private static final Map<String, ExecutorService> lanes = new ConcurrentHashMap<>();
    /** Queued sends being tried right now, and when each device was last tried, so triggers don't pile up attempts. */
    private static final Map<String, Boolean> running = new ConcurrentHashMap<>();
    private static final Map<String, Long> tried = new ConcurrentHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Sends() { }

    /** Runs {@code work} after earlier sends to the same device; sends to different devices run side by side. */
    static void submit(String deviceId, Runnable work) {
        lanes.computeIfAbsent(deviceId.toLowerCase(java.util.Locale.ROOT), k -> Executors.newSingleThreadExecutor()).execute(work);
    }

    /** The network couldn't reach the device (as opposed to an answer from it, or a problem with the files). */
    static boolean unreachable(Throwable e) {
        if (e instanceof ConnectException || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException) return true;
        String m = e.getMessage();
        if (m != null && (m.startsWith("unreachable") || m.startsWith("direct_failed") || m.startsWith("wifi_off")
                || m.contains("isn't on your local network") || m.contains("No reachable address") || m.contains("No saved address")
                || m.contains("ENETUNREACH") || m.contains("EHOSTUNREACH") || m.contains("ECONNREFUSED"))) return true;
        return e instanceof SocketException && !(e instanceof javax.net.ssl.SSLException);
    }

    /**
     * Gets a paired device ready: on the same network (announced, or answering at a saved address), else through its
     * direct link when {@code direct} allows joining it. Throws "unreachable: …" when neither works.
     */
    static Reached reach(HopApp app, Peer.Device d, boolean direct) throws Exception {
        Discovery discovery = ReceiveService.activeDiscovery;
        if (discovery != null) for (Discovery.Nearby n : discovery.list()) {
            if (!n.id.equalsIgnoreCase(d.id)) continue;
            d.addresses.remove(n.address);
            d.addresses.add(0, n.address);
            d.port = n.port;
            return new Reached(d, false);
        }
        if (LocalSockets.sameNetwork(app, d.addresses)) {
            try {
                app.peer().ping(d);
                Peer.Device fresh = app.book().get(d.id);
                return new Reached(fresh != null ? fresh : d, false);
            } catch (Exception e) {
                if (!unreachable(e)) throw e;
            }
        }
        if (direct && d.hasLink()) {
            app.live().connectingDirectly(d.label());
            app.directLink().use(d.linkSsid, d.linkKey, d.linkIp, 60000);
            try {
                d.addresses.remove(d.linkIp);
                d.addresses.add(0, d.linkIp);
                app.peer().ping(d);
                return new Reached(d, true);
            } catch (Exception e) {
                app.directLink().done();
                throw e;
            }
        }
        throw new IOException("unreachable: " + d.label() + " isn't on this network");
    }

    /**
     * Sends one job, blocking. A device that can't be reached gets the job queued (it goes when the device is back);
     * everything else ends as a result in Live, Activity and a notification, as before.
     */
    static void run(HopApp app, Job job, boolean direct) {
        Peer.Device d = app.book().get(job.deviceId);
        if (d == null) {
            dequeue(app, job.id);
            return;
        }
        String label = d.label();
        app.live().connecting(label);
        Reached reached;
        try {
            reached = reach(app, d, direct);
        } catch (Exception e) {
            if (job.backup) Backup.ended(app);
            if (unreachable(e) && job.backup) {
                // Backups don't wait in the queue: the next round picks the files up again.
                app.live().clear(label);
                Backup.waiting(app, label);
            } else if (unreachable(e)) {
                boolean fresh = !queued(app, job.id);
                queue(app, job);
                app.live().queued(label);
                if (fresh) app.notices().queued(d.id, label, job.textOnly() ? "Your text" : job.summary);
            } else {
                app.live().failedToStart(label, ErrorText.forDevice(e, label));
                app.notices().couldNotSend(label, ErrorText.forDevice(e, label));
                dequeue(app, job.id);
            }
            return;
        }
        try {
            for (String text : job.texts) {
                if (app.peer().sendText(reached.device, text, job.clip)) {
                    app.saveText(false, label, text);
                    app.live().textSent(label);
                    toast(app, (job.clip ? "Clipboard sent to " : "Sent to ") + label);
                }
            }
            List<Object> shown = new ArrayList<>();
            List<Peer.FileItem> items = items(app, job, shown);
            if (!items.isEmpty()) {
                app.sendingFrom(label, shown);
                app.peer().send(reached.device, items, job.merge);
                if (job.backup) Backup.sent(app, job);
            }
            dequeue(app, job.id);
        } catch (Exception e) {
            // Peer.send already reported a transfer that started; this covers what failed before it did.
            Live.Item item = app.live().outgoing(label);
            // It was announced a moment ago but has just gone (asleep, out of range): wait for it like any other away device.
            if (unreachable(e) && !job.backup && (item == null || item.connecting())) {
                boolean fresh = !queued(app, job.id);
                queue(app, job);
                app.live().queued(label);
                if (fresh) app.notices().queued(d.id, label, job.textOnly() ? "Your text" : job.summary);
                return;
            }
            String reason = e.getMessage() != null && e.getMessage().startsWith("cancelled") ? "You cancelled it." : ErrorText.forDevice(e, label);
            if (item == null || !item.finished() || item.failure != null) {
                app.live().failedToStart(label, reason);
                app.notices().couldNotSend(label, reason);
            }
            dequeue(app, job.id);
        } finally {
            if (reached.viaLink) app.directLink().done();
            if (job.backup) Backup.ended(app);
        }
    }

    /**
     * Folders first (each file with its place in the folder, listed now so it's current), then loose files. {@code shown}
     * gets the first 50 as [name, uri] pairs, so Activity can open them later.
     */
    private static List<Peer.FileItem> items(HopApp app, Job job, List<Object> shown) {
        List<Peer.FileItem> items = new ArrayList<>();
        for (Uri tree : job.trees) {
            for (FolderScan.Item file : FolderScan.files(app.getContentResolver(), tree)) {
                items.add(new Peer.FileItem(file.name, file.size, () -> app.getContentResolver().openInputStream(file.uri), file.folder));
                if (shown.size() < 50) shown.add(java.util.Arrays.asList(file.folder == null ? file.name : file.folder + "/" + file.name, file.uri.toString()));
            }
        }
        for (String[] item : job.listed) {
            Uri uri = Uri.parse(item[0]);
            items.add(new Peer.FileItem(item[1], Long.parseLong(item[3]), () -> app.getContentResolver().openInputStream(uri), item[2]));
            if (shown.size() < 50) shown.add(java.util.Arrays.asList(item[2] == null ? item[1] : item[2] + "/" + item[1], item[0]));
        }
        for (Uri uri : job.files) {
            String name = uri.getLastPathSegment();
            long size = -1;
            try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0);
                    if (!c.isNull(1)) size = c.getLong(1);
                }
            } catch (Exception ignored) {
            }
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) size = new java.io.File(uri.getPath()).length();
            if (name == null) name = "file";
            items.add(new Peer.FileItem(name, size, () -> app.getContentResolver().openInputStream(uri)));
            if (shown.size() < 50) shown.add(java.util.Arrays.asList(name, uri.toString()));
        }
        return items;
    }

    /** Text typed or shared in HopDrop is kept as a small .txt in the app's cache; it travels as text when the other side can show it. */
    static String textOf(Context context, Uri uri) {
        if (uri == null || !(context.getPackageName() + ".sharedtext").equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) return null;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 4 * Peer.MAX_TEXT) return null;
            }
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return text.isEmpty() || text.length() > Peer.MAX_TEXT ? null : text;
        } catch (Exception e) {
            return null;
        }
    }

    private static void toast(Context context, String text) {
        main.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    // ---- The queue: sends waiting for their device ----

    private static SharedPreferences store(Context c) { return c.getSharedPreferences("queue_v1", Context.MODE_PRIVATE); }

    @SuppressWarnings("unchecked")
    static synchronized List<Job> queued(Context c) {
        List<Job> jobs = new ArrayList<>();
        try {
            for (Object o : (List<Object>) Json.parseObject(store(c).getString("data", "{\"items\":[]}")).get("items"))
                jobs.add(Job.from((Map<String, Object>) o));
        } catch (Exception ignored) {
        }
        return jobs;
    }

    private static synchronized void save(Context c, List<Job> jobs) {
        List<Object> items = new ArrayList<>();
        for (Job j : jobs) items.add(j.json());
        store(c).edit().putString("data", Json.string(Json.obj("items", items))).apply();
    }

    static synchronized boolean queued(Context c, String jobId) {
        for (Job j : queued(c)) if (j.id.equals(jobId)) return true;
        return false;
    }

    static synchronized void queue(HopApp app, Job job) {
        if (!queued(app, job.id)) keepAccess(app, job);
        List<Job> jobs = queued(app);
        jobs.removeIf(j -> j.id.equals(job.id));
        jobs.add(job);
        save(app, jobs);
        app.changed();
    }

    static synchronized void dequeue(HopApp app, String jobId) {
        deleteCopies(app, jobId);
        List<Job> jobs = queued(app);
        if (jobs.removeIf(j -> j.id.equals(jobId))) {
            save(app, jobs);
            app.changed();
        }
    }

    /** The user gave up on the waiting sends to a device. */
    static synchronized void cancelQueued(HopApp app, String deviceId) {
        List<Job> jobs = queued(app);
        for (Job j : jobs) if (j.deviceId.equalsIgnoreCase(deviceId)) deleteCopies(app, j.id);
        if (jobs.removeIf(j -> j.deviceId.equalsIgnoreCase(deviceId))) {
            save(app, jobs);
            app.changed();
        }
    }

    /**
     * A waiting send must still be able to read its files later (after the app or phone restarts). Files picked in Android's
     * file picker keep their permission; the rest (shared from other apps, from the clipboard) are copied into HopDrop's own
     * storage until they're sent, unless that would take more than 500 MB (then they work while HopDrop keeps running).
     */
    private static void keepAccess(HopApp app, Job job) {
        java.io.File dir = new java.io.File(app.getFilesDir(), "waiting/" + job.id);
        long copied = 0;
        for (int i = 0; i < job.files.size(); i++) {
            Uri uri = job.files.get(i);
            if ((app.getPackageName() + ".sharedtext").equals(uri.getAuthority()) || "file".equals(uri.getScheme())) continue;
            try {
                app.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                continue;
            } catch (Exception notPersistable) {
                // Copy it below.
            }
            String name = "file";
            long size = -1;
            try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0);
                    if (!c.isNull(1)) size = c.getLong(1);
                }
            } catch (Exception ignored) {
            }
            if (size < 0 || copied + size > 500L * 1024 * 1024) continue;
            java.io.File target = new java.io.File(new java.io.File(dir, String.valueOf(i)), com.hop.drop.core.FileNames.sanitize(name));
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                if (in == null || !target.getParentFile().mkdirs() && !target.getParentFile().isDirectory()) continue;
                try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                job.files.set(i, Uri.fromFile(target));
                copied += size;
            } catch (Exception e) {
                target.delete();
            }
        }
    }

    private static void deleteCopies(Context c, String jobId) {
        java.io.File dir = new java.io.File(c.getFilesDir(), "waiting/" + jobId);
        java.io.File[] parts = dir.listFiles();
        if (parts != null) for (java.io.File part : parts) {
            java.io.File[] files = part.listFiles();
            if (files != null) for (java.io.File f : files) f.delete();
            part.delete();
        }
        dir.delete();
    }

    static int waitingFor(Context c, String deviceId) {
        int n = 0;
        for (Job j : queued(c)) if (j.deviceId.equalsIgnoreCase(deviceId)) n++;
        return n;
    }

    /**
     * Tries the waiting sends again: for one device ({@code deviceId}) or every device that has some (null). Runs in
     * HopDrop's process (the receiver's foreground service keeps it alive). {@code direct}: the user is on screen, so the
     * phone may join a PC's direct link for it; in the background only devices on the same network are tried.
     */
    static void retry(HopApp app, String deviceId, boolean direct) {
        long now = System.currentTimeMillis();
        for (Job job : queued(app)) {
            if (deviceId != null && !job.deviceId.equalsIgnoreCase(deviceId)) continue;
            if (running.putIfAbsent(job.id, true) != null) continue;
            Long last = tried.get(job.id);
            if (last != null && now - last < 20000) { running.remove(job.id); continue; }
            tried.put(job.id, now);
            submit(job.deviceId, () -> {
                try {
                    // Cancelled while it waited its turn: drop it.
                    if (queued(app, job.id)) run(app, job, direct);
                } finally {
                    running.remove(job.id);
                }
            });
        }
    }

    /** A short summary of what a list of files is ("3 files"), for notifications about waiting sends. */
    static String summary(int files, int folders) {
        if (folders == 0) return Format.files(files);
        if (files == 0) return Format.folders(folders);
        return Format.folders(folders) + " and " + Format.files(files);
    }

}
