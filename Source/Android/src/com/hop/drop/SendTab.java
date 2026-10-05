package com.hop.drop;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.hop.drop.core.Format;
import com.hop.drop.core.Peer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SendTab {
    private final MainActivity activity;
    private final Ui ui;
    private final Map<Uri, FileDetails> details = new HashMap<>();
    private final Map<String, DeviceRow> rows = new HashMap<>();
    private int animIndex = 0;

    private static final class FileDetails {
        final String name;
        final long size;

        FileDetails(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static final class DeviceRow {
        Peer.Device device;
        LinearLayout presence;
        TextView status;
        Ui.Bar bar;
    }

    SendTab(MainActivity activity) {
        this.activity = activity;
        ui = activity.ui;
    }

    void show(LinearLayout page) {
        rows.clear();
        animIndex = 0;
        showFiles(page);
        showDevices(page);
        showBluetooth(page);
    }

    private void showFiles(LinearLayout page) {
        boolean empty = activity.nothingSelected();
        LinearLayout card = ui.card(page, "Files to send", empty ? null : "Clear all", () -> {
            activity.selected.clear();
            activity.sendFolders.clear();
            activity.refreshCurrent();
        });
        long total = 0;
        boolean unknown = false, counting = false;
        for (Uri uri : activity.selected) {
            long size = details(uri).size;
            if (size < 0) unknown = true;
            else total += size;
        }
        for (MainActivity.SendFolder folder : activity.sendFolders) {
            if (folder.files < 0) counting = true;
            if (folder.sizeUnknown) unknown = true;
            total += folder.bytes;
        }
        if (empty) {
            ui.empty(card, R.drawable.ic_file_other, "No files yet",
                    "Add files or a folder here, or share files to HopDrop from any app.");
        } else {
            String summary = activity.selectionSummary() + "  ·  "
                    + (counting ? "counting…" : (unknown ? "at least " : "") + Format.size(total));
            ui.add(card, ui.text(summary, 14, ui.muted, false), 2);
            for (MainActivity.SendFolder folder : new ArrayList<>(activity.sendFolders)) addFolder(card, folder);
            for (Uri uri : new ArrayList<>(activity.selected)) addFile(card, uri);
        }
        // Two rows, so the labels fit on narrow screens and with larger text.
        ui.add(card, ui.buttons(ui.button("Add files", R.drawable.ic_action_add, activity::pickFiles, Ui.PRIMARY)), 16);
        ui.add(card, ui.buttons(
                ui.button("Photos", R.drawable.ic_action_photos, activity::pickPhotos, Ui.TONAL),
                ui.button("Folder", R.drawable.ic_action_folder, activity::pickFolder, Ui.TONAL)), 8);
        TextView tip = ui.text("Tip: in the file picker, long-press a file to select several.", 13, ui.muted, false);
        ui.add(card, tip, 10);
    }

    private void addFile(LinearLayout card, Uri uri) {
        FileDetails file = details(uri);
        int resource = iconFor(file.name);
        ImageView icon = ui.badge(resource, ui.blue, activity.getColor(tileColor(resource)), 44, false);
        ImageView remove = ui.iconButton(R.drawable.ic_action_remove, "Remove " + file.name, () -> {
            activity.selected.remove(uri);
            activity.refreshCurrent();
        });
        LinearLayout row = ui.listRow(icon, file.name, Format.size(file.size), remove, null);
        row.setBackground(null);
        row.setPadding(0, ui.dp(4), 0, ui.dp(4));
        ui.add(card, row, 4);
        animateListItem(row);
    }

    /** A whole folder is one row: it's sent as a folder, with everything inside it. */
    private void addFolder(LinearLayout card, MainActivity.SendFolder folder) {
        ImageView icon = ui.badge(R.drawable.ic_action_folder, ui.onAmberContainer, activity.getColor(R.color.tile_archive),
                44, false);
        ImageView remove = ui.iconButton(R.drawable.ic_action_remove, "Remove folder " + folder.name, () -> {
            activity.sendFolders.remove(folder);
            activity.refreshCurrent();
        });
        String detail = folder.files < 0 ? "Folder · counting files…"
                : "Folder · " + Format.files(folder.files) + " · " + (folder.sizeUnknown ? "at least " : "")
                + Format.size(folder.bytes);
        LinearLayout row = ui.listRow(icon, folder.name, detail, remove, null);
        row.setBackground(null);
        row.setPadding(0, ui.dp(4), 0, ui.dp(4));
        row.setContentDescription("Folder " + folder.name + ", " + detail);
        ui.add(card, row, 4);
        animateListItem(row);
    }

    private void showDevices(LinearLayout page) {
        LinearLayout card = ui.card(page, "Send to");
        List<Peer.Device> devices = activity.app.book().all();
        if (devices.isEmpty()) {
            ui.empty(card, R.drawable.ic_tab_devices, "No paired devices yet",
                    "Pair once in Devices. After that, just tap a device here to send.");
            ui.add(card, ui.button("Pair a device", R.drawable.ic_tab_devices, () -> activity.showDevicesTab(),
                    Ui.TONAL), 4);
            return;
        }
        ui.add(card, ui.text(activity.nothingSelected() ? "Add files above, then tap a device."
                : "Tap a device to send " + activity.selectionSummary() + ".", 14, ui.muted, false), 2);
        for (Peer.Device device : devices) addDevice(card, device);
    }

    private void addDevice(LinearLayout card, Peer.Device device) {
        boolean windows = "windows".equals(device.platform);
        boolean online = activity.online(device.id);
        ImageView avatar = ui.badge(windows ? R.drawable.ic_platform_laptop : R.drawable.ic_platform_phone,
                ui.onPrimaryContainer, ui.primaryContainer, 44, true);
        TextView send = ui.chip("Send", ui.white, ui.buttonBlue);
        LinearLayout row = ui.listRow(avatar, device.label(), null, send, () -> activity.send(device));
        LinearLayout labels = (LinearLayout) row.getChildAt(1);
        LinearLayout statusLine = ui.status(online ? "Online" : SendTab.lastSeen(device.lastSeen),
                online ? ui.success : ui.muted);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = ui.dp(2);
        labels.addView(statusLine, params);
        TextView status = ui.text("", 13, ui.ink, false);
        status.setMaxLines(2);
        status.setVisibility(View.GONE);
        labels.addView(status, params);
        Ui.Bar bar = ui.bar(ui.buttonBlue);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, ui.dp(6));
        barParams.topMargin = ui.dp(6);
        labels.addView(bar, barParams);
        row.setContentDescription("Send to " + device.label() + ", " + (online ? "online" : "offline"));
        row.setOnLongClickListener(view -> {
            new android.app.AlertDialog.Builder(activity).setTitle(device.label())
                    .setItems(new String[]{"Rename", "Remove"}, (dialog, which) -> {
                        if (which == 0) activity.rename(device);
                        else activity.remove(device);
                    }).show();
            return true;
        });
        DeviceRow entry = new DeviceRow();
        entry.device = device;
        entry.presence = statusLine;
        entry.status = status;
        entry.bar = bar;
        rows.put(device.label(), entry);
        bind(entry);
        ui.add(card, row, 8);
        animateListItem(row);
    }

    private void animateListItem(View row) {
        if (activity.entering && animIndex < 12 && Ui.animationsEnabled()) {
            row.setAlpha(0f);
            activity.handler.postDelayed(() -> ui.fadeIn(row), animIndex * 20);
        }
        animIndex++;
    }

    private void showBluetooth(LinearLayout page) {
        LinearLayout card = ui.card(page, "No shared Wi-Fi?");
        ui.add(card, ui.text("Send with Bluetooth instead. It's slower, and the laptop must be waiting: "
                + "HopDrop on Windows → Receive via Bluetooth.", 14, ui.muted, false), 4);
        ui.add(card, ui.button("Send with Bluetooth", R.drawable.ic_action_bluetooth, activity::bluetooth,
                Ui.OUTLINE), 12);
    }

    /** Refreshes the live status line under each device without rebuilding the page. */
    void updateProgress() {
        for (DeviceRow row : rows.values()) bind(row);
    }

    private void bind(DeviceRow row) {
        boolean online = activity.online(row.device.id);
        int color = online ? ui.success : ui.muted;
        row.presence.getChildAt(0).setBackground(ui.shape(color, 5));
        TextView presence = (TextView) row.presence.getChildAt(1);
        presence.setText(online ? "Online" : SendTab.lastSeen(row.device.lastSeen));
        presence.setTextColor(color);
        Live.Item item = activity.app.live().outgoing(row.device.label());
        if (item == null) {
            row.status.setVisibility(View.GONE);
            row.bar.setVisibility(View.GONE);
            return;
        }
        row.status.setVisibility(View.VISIBLE);
        boolean active = !item.finished();
        row.bar.setVisibility(active ? View.VISIBLE : View.GONE);
        if (item.connecting()) {
            row.status.setText("Connecting…");
            row.status.setTextColor(ui.muted);
            row.bar.set(-1, ui.buttonBlue);
        } else if (item.failure != null) {
            row.status.setText(item.failure);
            row.status.setTextColor(ui.danger);
        } else if (item.result != null) {
            boolean ok = item.result.error == null;
            row.status.setText(ok ? "Sent " + Format.files(item.result.files.size()) + " · "
                    + Format.size(item.result.bytes) : item.result.error.startsWith("cancelled")
                    ? Notices.cancelReason(item.result) : ErrorText.forDevice(item.result.error, item.peer));
            row.status.setTextColor(ok ? ui.success : ui.danger);
        } else if (item.progress.reconnecting) {
            row.status.setText("Connection dropped · reconnecting…");
            row.status.setTextColor(ui.muted);
            row.bar.set(-1, ui.buttonBlue);
        } else if (item.progress.waiting) {
            row.status.setText("Waiting for " + item.peer + " to accept…");
            row.status.setTextColor(ui.muted);
            row.bar.set(-1, ui.buttonBlue);
        } else {
            Peer.Progress p = item.progress;
            row.status.setText(Format.amount(p.done, p.total) + (p.secondsLeft >= 0
                    ? " · " + Format.duration(p.secondsLeft) + " left" : ""));
            row.status.setTextColor(ui.ink);
            row.bar.glide(p.total > 0 ? p.done / (float) p.total : -1, ui.buttonBlue);
        }
    }

    static String lastSeen(long time) {
        if (time <= 0) return "Offline";
        long minutes = Math.max(1, (System.currentTimeMillis() - time) / 60000);
        if (minutes < 60) return "Seen " + minutes + " min ago";
        long hours = minutes / 60;
        if (hours < 24) return "Seen " + hours + " h ago";
        return "Seen " + hours / 24 + (hours / 24 == 1 ? " day ago" : " days ago");
    }

    private FileDetails details(Uri uri) {
        FileDetails cached = details.get(uri);
        if (cached != null) return cached;
        String name = uri.getLastPathSegment() == null ? "File" : uri.getLastPathSegment();
        long size = -1;
        try (Cursor cursor = activity.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) name = cursor.getString(0);
                if (!cursor.isNull(1)) size = cursor.getLong(1);
            }
        } catch (Exception ignored) {
        }
        FileDetails file = new FileDetails(name, size);
        details.put(uri, file);
        return file;
    }

    private int iconFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp|heic|heif|bmp)$")) return R.drawable.ic_file_image;
        if (lower.matches(".*\\.(mp4|mov|mkv|avi|webm|3gp)$")) return R.drawable.ic_file_video;
        if (lower.matches(".*\\.(mp3|m4a|wav|flac|ogg|aac|opus)$")) return R.drawable.ic_file_audio;
        if (lower.matches(".*\\.(pdf|doc|docx|txt|rtf|xls|xlsx|ppt|pptx|csv|md)$")) {
            return R.drawable.ic_file_document;
        }
        if (lower.matches(".*\\.(zip|rar|7z|tar|gz|apk)$")) return R.drawable.ic_file_archive;
        return R.drawable.ic_file_other;
    }

    private int tileColor(int icon) {
        if (icon == R.drawable.ic_file_image) return R.color.tile_image;
        if (icon == R.drawable.ic_file_video) return R.color.tile_video;
        if (icon == R.drawable.ic_file_audio) return R.color.tile_audio;
        if (icon == R.drawable.ic_file_document) return R.color.tile_document;
        if (icon == R.drawable.ic_file_archive) return R.color.tile_archive;
        return R.color.tile_other;
    }
}
