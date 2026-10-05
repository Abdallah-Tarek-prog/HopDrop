package com.hop.drop;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.IBinder;
import android.util.Log;
import com.hop.drop.net.Discovery;

/** Listens for pairing and incoming files, and keeps this phone discoverable (spec R11). */
public final class ReceiveService extends Service {
    public static final String STOP_WHEN_IDLE = "com.hop.drop.STOP_WHEN_IDLE";
    public static volatile Discovery activeDiscovery;
    /** True while the TCP listener is accepting connections. */
    public static volatile boolean running;
    private Discovery discovery;
    private volatile boolean stopWhenIdle;
    private BroadcastReceiver networkChanges;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** "Receive in the background": on unless the user turned it off, so files and their notifications arrive any time. */
    static boolean background(Context context) {
        return context.getSharedPreferences("settings_v2", MODE_PRIVATE).getBoolean("background", true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        HopApp app = (HopApp) getApplication();
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            // Started with startForegroundService(): Android kills the app unless startForeground() runs first.
            try {
                startForeground(Notices.RECEIVER, app.notices().ready());
            } catch (RuntimeException error) {
                Log.w("HopDrop", "Receiver could not become a foreground service", error);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }
        if (app.peer() == null) {
            app.notices().receiverProblem("HopDrop couldn't load this phone's identity. Restart the phone and try again.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if ("cancel".equals(action)) {
            app.peer().cancelIncoming();
            if (stopWhenIdle) stopWhenIdle(app, startId);
            return START_NOT_STICKY;
        }
        if (STOP_WHEN_IDLE.equals(action)) {
            stopWhenIdle = true;
            stopWhenIdle(app, startId);
            return START_NOT_STICKY;
        }
        if (action != null) return START_NOT_STICKY;
        stopWhenIdle = false;
        startReceiving(app);
        return background(this) ? START_STICKY : START_NOT_STICKY;
    }

    /**
     * Stops once no transfer is running. stopSelf(startId) only stops if no newer start arrived meanwhile (for
     * example the user reopened HopDrop). A plain stopSelf() could stop the service between startForegroundService()
     * and startForeground(), which makes Android kill the app mid-transfer.
     */
    private void stopWhenIdle(HopApp app, int startId) {
        new Thread(() -> {
            while (stopWhenIdle && app.peer().isReceiving()) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            main.post(() -> {
                if (stopWhenIdle && !background(this)) stopSelf(startId);
            });
        }, "HopDrop stop when idle").start();
    }

    private void startReceiving(HopApp app) {
        try {
            app.peer().start(7410);
            running = true;
            app.notices().clearReceiverProblem();
        } catch (Exception error) {
            Log.w("HopDrop", "Receiver could not listen on port 7410", error);
            running = false;
            app.notices().receiverProblem("Another app is using HopDrop's network port, or the network isn't ready. "
                    + "Close HopDrop and open it again.");
        }
        if (discovery == null) {
            discovery = new Discovery(this, app.peer(), app::changed);
            discovery.setForeground(app.isVisible());
            discovery.setHidden(app.hidden());
            activeDiscovery = discovery;
            networkChanges = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent changed) {
                    if (discovery != null) discovery.networkChanged();
                    app.notices().showReady();
                    app.changed();
                }
            };
            registerReceiver(networkChanges, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
        if (!discovery.isActive()) {
            try {
                discovery.start();
            } catch (Exception error) {
                Log.w("HopDrop", "Discovery could not start; devices can still connect by address", error);
            }
        }
        app.notices().showReady();
        app.changed();
    }

    @Override
    public void onDestroy() {
        running = false;
        activeDiscovery = null;
        if (networkChanges != null) unregisterReceiver(networkChanges);
        if (discovery != null) discovery.close();
        HopApp app = (HopApp) getApplication();
        if (app.peer() != null) app.peer().stop();
        app.changed();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
