package com.hop.drop;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hop.drop.core.Format;
import com.hop.drop.core.Json;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ActivityTab {
    private final MainActivity activity;
    private final Ui ui;

    ActivityTab(MainActivity activity) {
        this.activity = activity;
        ui = activity.ui;
    }

    void show(LinearLayout page) {
        LinearLayout card = ui.card(page, "Activity", "Clear", this::clear);
        try {
            Map<String,Object> saved = Json.parseObject(activity.getSharedPreferences("history_v2", 0)
                    .getString("data", "{\"items\":[]}"));
            List<?> entries = (List<?>) saved.get("items");
            if (entries == null || entries.isEmpty()) {
                ui.empty(card, R.drawable.ic_tab_activity, "No transfers yet",
                        "Files you send and receive will appear here.");
                return;
            }
            String day = "";
            SimpleDateFormat group = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());
            for (Object value : entries) {
                Map<String,Object> entry = (Map<String,Object>) value;
                long at = ((Number) entry.get("at")).longValue();
                String currentDay = group.format(new Date(at));
                if (!currentDay.equals(day)) {
                    day = currentDay;
                    ui.add(card, ui.text(day, 13, ui.muted, true), 16);
                }
                addEntry(card, entry, at);
            }
        } catch (Exception error) {
            ui.empty(card, R.drawable.ic_tab_activity, "No activity yet",
                    "Transfers will appear here after you send or receive files.");
        }
    }

    private void clear() {
        new AlertDialog.Builder(activity).setTitle("Clear activity?")
                .setMessage("This clears the list only. Files you received stay where they are.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> {
                    activity.getSharedPreferences("history_v2", 0).edit().remove("data").apply();
                    activity.refreshCurrent();
                }).show();
    }

    private void addEntry(LinearLayout card, Map<String,Object> entry, long at) {
        boolean incoming = Boolean.TRUE.equals(entry.get("incoming"));
        List<?> files = (List<?>) entry.get("files");
        // Big transfers keep only the first names; "count" has the real number.
        int count = entry.get("count") instanceof Number ? ((Number) entry.get("count")).intValue()
                : files == null ? 0 : files.size();
        String peer = String.valueOf(entry.get("peer"));
        String error = entry.get("error") == null ? null : String.valueOf(entry.get("error"));
        boolean ok = error == null;
        boolean cancelled = !ok && error.startsWith("cancelled");
        int fg = ok ? (incoming ? ui.success : ui.onPrimaryContainer) : cancelled ? ui.onAmberContainer : ui.danger;
        int bg = ok ? (incoming ? ui.successContainer : ui.primaryContainer) : cancelled ? ui.amberContainer
                : ui.dangerContainer;
        ImageView icon = ui.badge(ok ? (incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out)
                : R.drawable.ic_status_error, fg, bg, 44, true);
        icon.setContentDescription(incoming ? "Received" : "Sent");
        java.util.List<String> fileNames = new java.util.ArrayList<>();
        if (files != null) for (Object file : files) fileNames.add(String.valueOf(file));
        String names = fileNames.isEmpty() ? "No files were completed" : Format.list(fileNames, 6);
        long bytes = entry.get("bytes") instanceof Number ? ((Number) entry.get("bytes")).longValue() : -1;
        String time = new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(at));
        String result = ok ? (incoming ? "Received" : "Sent") : cancelled ? "Cancelled"
                : ErrorText.forDevice(error, peer);
        LinearLayout row = ui.listRow(icon, (incoming ? "From " : "To ") + peer, names,
                incoming && count > 0 ? ui.icon(R.drawable.ic_chevron_right, ui.muted, null) : null,
                incoming && count > 0 ? () -> openReceived(entry) : null);
        LinearLayout labels = (LinearLayout) row.getChildAt(1);
        int offered = entry.get("offered") instanceof Number ? ((Number) entry.get("offered")).intValue() : -1;
        String amount = ok ? Format.files(count) + "  ·  " + Format.size(bytes)
                : (offered > 0 ? count + " of " + Format.files(offered) : Format.files(count))
                + "  ·  stopped after " + Format.size(bytes);
        TextView detail = ui.text(amount + "  ·  " + time + "  ·  " + result, 13, ok ? ui.muted : fg, !ok);
        detail.setMaxLines(3);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(4);
        labels.addView(detail, params);
        if (incoming && count > 0) row.setContentDescription("Open files received from " + peer);
        ui.add(card, row, 8);
    }

    private void openReceived(Map<String,Object> entry) {
        List<?> files = (List<?>) entry.get("files");
        if (files == null || files.isEmpty()) {
            activity.toast("No completed files in this transfer.");
            return;
        }
        List<?> uris = (List<?>) entry.get("uris");
        if (files.size() == 1) {
            openFile((String) files.get(0), savedUri(uris, 0));
            return;
        }
        String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = (String) files.get(i);
        new AlertDialog.Builder(activity).setTitle("Received files")
                .setItems(names, (dialog, which) -> openFile(names[which], savedUri(uris, which)))
                .setNegativeButton("Close", null).show();
    }

    private String savedUri(List<?> uris, int index) {
        return uris != null && uris.size() > index ? (String) uris.get(index) : null;
    }

    private void openFile(String name, String savedUri) {
        Uri uri = savedUri == null ? activity.app.receivedUri(name) : Uri.parse(savedUri);
        if (uri == null) {
            activity.toast("This file is no longer in the receive folder.");
            return;
        }
        String mime = activity.getContentResolver().getType(uri);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime == null ? "*/*" : mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (Exception error) {
            activity.toast("No app can open this file.");
        }
    }
}

