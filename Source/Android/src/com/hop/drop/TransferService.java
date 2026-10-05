package com.hop.drop;

import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.OpenableColumns;
import com.hop.drop.core.Format;
import com.hop.drop.core.Peer;
import com.hop.drop.net.Discovery;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Sends files to a paired device, one transfer at a time (spec 6, TransferService). */
public final class TransferService extends Service {
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private volatile Thread current;
    /** Sends queued or running; the notification stays (and Android keeps the service) until the last one ends. */
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean cancelRequested;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if ("cancel".equals(intent.getAction())) {
            cancelRequested = true;
            HopApp app = (HopApp) getApplication();
            if (app.peer() != null) app.peer().cancelOutgoing();
            Thread t = current;
            if (t != null) t.interrupt();
            return START_NOT_STICKY;
        }
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra("files");
        ArrayList<Uri> trees = intent.getParcelableArrayListExtra("trees");
        String summary = intent.getStringExtra("summary");
        String id = intent.getStringExtra("device");
        HopApp app = (HopApp) getApplication();
        Peer.Device queued = app.book().get(id);
        String label = queued == null ? "the device" : queued.label();
        startForeground(Notices.OUTGOING, app.notification("transfers", "Connecting to " + label,
                "Getting ready to send " + (summary != null ? summary : Format.files(uris == null ? 0 : uris.size())), true, -1)
                .setSmallIcon(R.drawable.ic_direction_out).setShowWhen(false).build());
        pending.incrementAndGet();
        queue.execute(() -> {
            current = Thread.currentThread();
            cancelRequested = false;
            Peer.Device d = app.book().get(id);
            try {
                if (d == null) throw new IllegalArgumentException("not_paired: Device is no longer paired");
                Discovery discovery = ReceiveService.activeDiscovery;
                if (discovery != null) for (Discovery.Nearby n : discovery.list()) {
                    if (n.id.equalsIgnoreCase(id)) {
                        d.addresses.remove(n.address);
                        d.addresses.add(0, n.address);
                        d.port = n.port;
                        break;
                    }
                }
                List<Peer.FileItem> items = items(uris, trees);
                app.sendingFrom(d.label(), sources);
                app.peer().send(d, items);
            } catch (Exception e) {
                String reason = d == null ? "This device is no longer paired. Pair it again in Devices."
                        : cancelRequested ? "You cancelled it." : ErrorText.forDevice(e, label);
                Live.Item item = app.live().outgoing(label);
                if (item == null || !item.finished() || item.failure != null) {
                    app.live().failedToStart(label, reason);
                    app.notices().couldNotSend(label, reason);
                }
            } finally {
                current = null;
                if (pending.decrementAndGet() == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf(startId);
                }
            }
        });
        return START_NOT_STICKY;
    }

    /** Folders first (each file with its place inside the folder, listed now so it's current), then loose files. */
    /** The first 50 files of the transfer being prepared, as [name, uri] pairs for Activity. */
    private List<Object> sources = new ArrayList<>();

    private List<Peer.FileItem> items(List<Uri> uris, List<Uri> trees) {
        List<Peer.FileItem> items = new ArrayList<>();
        sources = new ArrayList<>();
        if (trees != null) {
            for (Uri tree : trees) {
                for (FolderScan.Item file : FolderScan.files(getContentResolver(), tree)) {
                    items.add(new Peer.FileItem(file.name, file.size, () -> getContentResolver().openInputStream(file.uri),
                            file.folder));
                    if (sources.size() < 50) sources.add(java.util.Arrays.asList(file.folder == null ? file.name
                            : file.folder + "/" + file.name, file.uri.toString()));
                }
            }
        }
        if (uris == null) return items;
        for (Uri uri : uris) {
            String name = uri.getLastPathSegment();
            long size = -1;
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0);
                    if (!c.isNull(1)) size = c.getLong(1);
                }
            } catch (Exception ignored) {
            }
            if (name == null) name = "file";
            items.add(new Peer.FileItem(name, size, () -> getContentResolver().openInputStream(uri)));
            if (sources.size() < 50) sources.add(java.util.Arrays.asList(name, uri.toString()));
        }
        return items;
    }

    /** Android 15 limits background data-sync work to six hours a day; stop cleanly instead of being killed. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        cancelRequested = true;
        HopApp app = (HopApp) getApplication();
        if (app.peer() != null) app.peer().cancelOutgoing();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        queue.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
