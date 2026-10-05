package com.hop.drop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!ReceiveService.background(context)) return;
        try {
            context.startForegroundService(new Intent(context, ReceiveService.class));
        } catch (RuntimeException ignored) {
            // Android may refuse a background service launch; opening the app starts it again.
        }
    }
}
