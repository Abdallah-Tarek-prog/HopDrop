package com.hop.drop;

import com.hop.drop.core.Peer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is moving right now, in both directions. Screens read it to draw live progress; entries for
 * finished transfers stay for a few seconds so the user sees how each one ended.
 */
final class Live {
    static final long KEEP_FINISHED_MS = 6000;

    static final class Item {
        final String key;
        final boolean incoming;
        final String peer;
        final Peer.Progress progress;
        final Peer.Result result;
        final String failure;
        final long updatedAt;

        Item(String key, boolean incoming, String peer, Peer.Progress progress, Peer.Result result, String failure) {
            this.key = key;
            this.incoming = incoming;
            this.peer = peer;
            this.progress = progress;
            this.result = result;
            this.failure = failure;
            updatedAt = System.currentTimeMillis();
        }

        boolean connecting() {
            return progress == null && result == null && failure == null;
        }

        boolean finished() {
            return result != null || failure != null;
        }
    }

    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Runnable changed;

    Live(Runnable changed) {
        this.changed = changed;
    }

    /** An outgoing transfer was requested and HopDrop is connecting to {@code peer}. */
    void connecting(String peer) {
        synchronized (this) {
            items.put(pendingKey(peer), new Item(pendingKey(peer), false, peer, null, null, null));
        }
        changed.run();
    }

    /** An outgoing transfer failed before any file moved (for example the device was unreachable). */
    void failedToStart(String peer, String reason) {
        synchronized (this) {
            items.put(pendingKey(peer), new Item(pendingKey(peer), false, peer, null, null, reason));
        }
        changed.run();
    }

    void progress(Peer.Progress p) {
        synchronized (this) {
            if (!p.incoming) items.remove(pendingKey(p.peer));
            items.put(p.transferId, new Item(p.transferId, p.incoming, p.peer, p, null, null));
        }
        changed.run();
    }

    void finished(Peer.Result r) {
        synchronized (this) {
            if (!r.incoming) items.remove(pendingKey(r.peer));
            Item old = items.get(r.transferId);
            items.put(r.transferId, new Item(r.transferId, r.incoming, r.peer, old == null ? null : old.progress, r, null));
        }
        changed.run();
    }

    /** Current items, oldest first, without finished ones that have been shown long enough. */
    synchronized List<Item> items() {
        long now = System.currentTimeMillis();
        items.values().removeIf(item -> item.finished() && now - item.updatedAt > KEEP_FINISHED_MS);
        return new ArrayList<>(items.values());
    }

    /** The newest outgoing item for a device label, or null. */
    synchronized Item outgoing(String peer) {
        Item found = null;
        for (Item item : items.values()) if (!item.incoming && item.peer.equals(peer)) found = item;
        return found;
    }

    synchronized boolean busy() {
        for (Item item : items.values()) if (!item.finished()) return true;
        return false;
    }

    private static String pendingKey(String peer) {
        return "pending:" + peer;
    }
}
