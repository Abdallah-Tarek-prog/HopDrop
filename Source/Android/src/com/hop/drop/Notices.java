package com.hop.drop;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import com.hop.drop.core.Format;
import com.hop.drop.core.Peer;
import com.hop.drop.net.LocalSockets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every notification HopDrop shows. Copy rules: the title says what is happening and with whom,
 * the collapsed line says how far along it is, the expanded text adds the file name and speed.
 */
final class Notices {
    static final int OUTGOING = 410;
    static final int PAIRING = 412;
    static final int RECEIVER = 413;
    static final int RECEIVER_PROBLEM = 414;
    private static final int OFFER = 20000;
    private final HopApp app;
    private final NotificationManager manager;
    /** When each transfer's notification was last updated: Android shows about one update a second anyway, so more is wasted work. */
    private final Map<String, Long> posted = new ConcurrentHashMap<>();

    Notices(HopApp app) {
        this.app = app;
        manager = app.getSystemService(NotificationManager.class);
    }

    /** The receiver's ongoing notification while it is idle. */
    Notification ready() {
        String name = app.peer() == null ? "this phone" : app.peer().name();
        List<LocalSockets.Address> addresses = LocalSockets.describe(app);
        String where = addresses.isEmpty() ? "Join a Wi-Fi network or turn on your hotspot"
                : "Visible as " + name + " · " + addresses.get(0).ip;
        return app.notification("transfers", "Ready to receive files", where, true, -1)
                .setSmallIcon(R.drawable.ic_notification).setShowWhen(false).build();
    }

    void showReady() {
        if (ReceiveService.running) manager.notify(RECEIVER, ready());
    }

    void progress(Peer.Progress p) {
        long at = SystemClock.elapsedRealtime();
        Long last = posted.get(p.transferId);
        if (last != null && at - last < 1000 && !p.waiting && !p.reconnecting) return;
        posted.put(p.transferId, at);
        if (p.reconnecting) {
            manager.notify(p.incoming ? RECEIVER : OUTGOING, app.notification("transfers", p.incoming
                    ? "Waiting for " + p.peer + " to reconnect" : "Reconnecting to " + p.peer,
                    "The connection dropped. HopDrop continues where it stopped.", true, -1)
                    .setSmallIcon(p.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out).setShowWhen(false).build());
            return;
        }
        if (p.waiting) {
            manager.notify(OUTGOING, app.notification("transfers", "Waiting for " + p.peer + " to accept",
                    Format.files(p.count) + " will start sending once they accept.", true, -1)
                    .setSmallIcon(R.drawable.ic_direction_out).setShowWhen(false).build());
            return;
        }
        String title = (p.incoming ? "Receiving " : "Sending ") + Format.files(p.count)
                + (p.incoming ? " from " : " to ") + p.peer;
        String left = p.secondsLeft < 0 ? "" : " · " + Format.duration(p.secondsLeft) + " left";
        String amount = Format.amount(p.done, p.total);
        String now = p.count == 1 ? p.file : p.file + "  (" + p.index + " of " + p.count + ")";
        Intent cancel = new Intent(app, p.incoming ? ReceiveService.class : TransferService.class).setAction("cancel");
        PendingIntent cancelAction = PendingIntent.getService(app, p.incoming ? 2 : 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = app.notification("transfers", title, amount + left, true, p.percent())
                .setSmallIcon(p.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out)
                .setStyle(new Notification.BigTextStyle().bigText(now + "\n" + amount + " · "
                        + Format.speed(p.speed) + left))
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(0, "Cancel", cancelAction);
        if (p.percent() >= 0) builder.setSubText(p.percent() + "%");
        manager.notify(p.incoming ? RECEIVER : OUTGOING, builder.build());
    }

    void result(Peer.Result r) {
        posted.remove(r.transferId);
        boolean ok = r.error == null;
        boolean cancelled = !ok && r.error.startsWith("cancelled");
        String title;
        if (ok) {
            title = (r.incoming ? "Received " : "Sent ") + Format.files(r.files.size())
                    + (r.incoming ? " from " : " to ") + r.peer;
        } else if (cancelled) {
            title = (r.incoming ? "Receiving from " : "Sending to ") + r.peer + " cancelled";
        } else {
            title = r.incoming ? "Receiving from " + r.peer + " stopped" : "Couldn't finish sending to " + r.peer;
        }
        String took = r.millis > 0 ? " in " + Format.duration(Math.max(1, r.millis / 1000)) : "";
        String where = r.incoming ? "Saved in " + r.folder : "Saved in " + r.folder + " on " + r.peer;
        String line;
        String details;
        if (ok) {
            line = Format.names(r.files) + " · " + Format.size(r.bytes);
            details = Format.list(r.files, 8) + "\n" + where + "\n" + Format.size(r.bytes) + took;
        } else {
            String reason = cancelled ? cancelReason(r) : ErrorText.forDevice(r.error, r.peer);
            String kept = r.files.isEmpty() ? "No files were " + (r.incoming ? "saved." : "delivered.")
                    : r.files.size() + " of " + r.offered + " files " + (r.incoming ? "were saved" : "arrived")
                    + " (" + Format.names(r.files) + "). " + where + ".";
            line = reason;
            details = reason + "\n" + kept;
        }
        // Files that arrived pop up on screen; everything else (sent, failed, cancelled) arrives quietly.
        Notification.Builder n = app.notification(ok && r.incoming ? "received" : "results", title, line, false, -1)
                .setSmallIcon(ok ? (r.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out)
                        : R.drawable.ic_status_error)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setAutoCancel(true);
        if (r.incoming && !r.files.isEmpty()) {
            Intent show = new Intent(app, MainActivity.class).putExtra("show_files", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            n.addAction(0, "Show files", PendingIntent.getActivity(app, 3, show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (r.files.size() == 1) {
                Uri uri = app.receivedUri(r.files.get(0));
                if (uri != null) {
                    String mime = app.getContentResolver().getType(uri);
                    Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime == null ? "*/*" : mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    n.addAction(0, "Open", PendingIntent.getActivity(app, 4, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                }
            }
        }
        if (r.incoming) showReady(); else manager.cancel(OUTGOING);
        manager.notify(1000 + (r.transferId.hashCode() & 0xffff), n.build());
    }

    /** "Ask before receiving": Accept and Decline right on the notification. It disappears when the offer expires. */
    void offer(Peer.Offer o) {
        int code = o.transferId.hashCode() & 0xffff;
        PendingIntent accept = PendingIntent.getBroadcast(app, code * 2, new Intent(app, OfferAnswer.class)
                .putExtra("id", o.transferId).putExtra("accept", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent decline = PendingIntent.getBroadcast(app, code * 2 + 1, new Intent(app, OfferAnswer.class)
                .putExtra("id", o.transferId).putExtra("accept", false), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String size = o.total >= 0 ? " · " + Format.size(o.total) : "";
        manager.notify(OFFER + code, app.notification("requests", o.peer + " wants to send you " + Format.files(o.files.size()),
                Format.names(o.files) + size, false, -1)
                .setSmallIcon(R.drawable.ic_direction_in)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setTimeoutAfter(Math.max(1000, o.deadline - System.currentTimeMillis()))
                .addAction(0, "Accept", accept)
                .addAction(0, "Decline", decline)
                .setAutoCancel(true).build());
    }

    void clearOffer(Peer.Offer o) {
        manager.cancel(OFFER + (o.transferId.hashCode() & 0xffff));
    }

    /** Who cancelled: this phone ("You cancelled the transfer" / "Transfer cancelled") or the other device. */
    static String cancelReason(Peer.Result r) {
        return r.error.contains("Peer cancelled") || r.error.contains("Sender cancelled")
                ? r.peer + " cancelled it." : "You cancelled it.";
    }

    /** An outgoing transfer that never started (unreachable device, missing file, …). */
    void couldNotSend(String peer, String reason) {
        manager.notify(1000 + (peer.hashCode() & 0xffff), app.notification("results", "Couldn't send to " + peer,
                reason, false, -1).setSmallIcon(R.drawable.ic_status_error)
                .setStyle(new Notification.BigTextStyle().bigText(reason)).setAutoCancel(true).build());
    }

    void receiverProblem(String reason) {
        manager.notify(RECEIVER_PROBLEM, app.notification("results", "HopDrop can't receive right now", reason,
                false, -1).setSmallIcon(R.drawable.ic_status_error)
                .setStyle(new Notification.BigTextStyle().bigText(reason)).setAutoCancel(true).build());
    }

    void clearReceiverProblem() {
        manager.cancel(RECEIVER_PROBLEM);
    }
}
