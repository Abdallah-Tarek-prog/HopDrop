package com.hop.drop;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.hop.drop.core.Format;
import com.hop.drop.core.Peer;
import com.hop.drop.net.Discovery;
import com.hop.drop.net.LocalSockets;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final String PREFS = "settings_v2";
    private static final int FILES = 1;
    private static final int PHOTOS = 2;
    private static final int TREE = 3;
    private static final int SCAN = 4;
    private static final int SEND_FOLDER = 6;
    private static final String[] TABS = {"Send", "Devices", "Activity", "Settings"};
    private static final int[] TAB_ICONS = {R.drawable.ic_tab_send, R.drawable.ic_tab_devices,
            R.drawable.ic_tab_activity, R.drawable.ic_tab_settings};
    /** Loose files to send (added one by one, or shared from another app). */
    final ArrayList<Uri> selected = new ArrayList<>();
    /** Whole folders to send: each stays one item and arrives as a folder, with its subfolders. */
    final ArrayList<SendFolder> sendFolders = new ArrayList<>();

    /** A folder in the Send list. Its files are counted in the background and listed again when it's sent. */
    static final class SendFolder {
        final Uri tree;
        String name;
        /** -1 while counting. */
        int files = -1;
        long bytes;
        boolean sizeUnknown;

        SendFolder(Uri tree, String name) {
            this.tree = tree;
            this.name = name;
        }
    }
    final ExecutorService work = Executors.newCachedThreadPool();
    final Handler handler = new Handler(Looper.getMainLooper());
    HopApp app;
    Ui ui;
    LinearLayout page;
    private ScrollView scroll;
    private LinearLayout bottom;
    private LinearLayout liveHost;
    private TextView status;
    private View statusDot;
    private LinearLayout statusChip;
    private TextView phoneName;
    private int tab;
    private final int[] scrollY = new int[4];
    private final Map<String, LiveCard> liveCards = new HashMap<>();
    /** True while a tab is being opened (not refreshed in place): its rows fade in. */
    boolean entering;
    private HopApp.Prompt shown;
    private AlertDialog promptDialog;
    private final java.util.Map<String, AlertDialog> offerDialogs = new java.util.HashMap<>();
    private AlertDialog qrDialog;
    private SendTab sendTab;
    private DevicesTab devicesTab;
    private ActivityTab activityTab;
    private SettingsTab settingsTab;

    /** Applies the Light / Dark choice from Settings on top of the system setting. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        int mode = base.getSharedPreferences(PREFS, MODE_PRIVATE).getInt("theme", 0);
        if (mode != 0) {
            Configuration override = new Configuration();
            override.uiMode = mode == 2 ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
            applyOverrideConfiguration(override);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (HopApp) getApplication();
        ui = new Ui(this);
        sendTab = new SendTab(this);
        devicesTab = new DevicesTab(this);
        activityTab = new ActivityTab(this);
        settingsTab = new SettingsTab(this);
        boolean askNotifications = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (askNotifications) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 61);
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 62);
        }
        if (state != null) {
            ArrayList<Uri> kept = state.getParcelableArrayList("selected");
            if (kept != null) selected.addAll(kept);
            ArrayList<Uri> trees = state.getParcelableArrayList("send_trees");
            ArrayList<String> names = state.getStringArrayList("send_tree_names");
            int[] counts = state.getIntArray("send_tree_files");
            long[] bytes = state.getLongArray("send_tree_bytes");
            if (trees != null && names != null && counts != null && bytes != null && names.size() == trees.size()) {
                for (int i = 0; i < trees.size(); i++) {
                    SendFolder folder = new SendFolder(trees.get(i), names.get(i));
                    folder.files = counts[i];
                    folder.bytes = Math.max(0, bytes[i]);
                    folder.sizeUnknown = bytes[i] < 0;
                    sendFolders.add(folder);
                }
            }
        } else share(getIntent());
        tab = state != null ? state.getInt("tab", 0) : getIntent().getBooleanExtra("show_files", false) ? 2 : 0;
        createShell();
        showTab(tab);
        if (state == null && !askNotifications) handler.post(this::offerBackgroundActivity);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 61) offerBackgroundActivity();
    }

    /**
     * Once: receiving in the background is on, but Android may still pause HopDrop to save battery, so files sent
     * while the screen is off wouldn't arrive. Offer the system's "allow background activity" question.
     */
    private void offerBackgroundActivity() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("background_offered", false) || !ReceiveService.background(this) || !batteryRestricted()
                || isFinishing()) return;
        prefs.edit().putBoolean("background_offered", true).apply();
        new AlertDialog.Builder(this).setTitle("Receive files any time")
                .setMessage("HopDrop receives files and tells you when they arrive, even when it's closed. "
                        + "It shows a small ongoing notification and uses a little battery.\n\n"
                        + "Allow it to run in the background so files also arrive while the screen is off. "
                        + "You can turn background receiving off in Settings.")
                .setPositiveButton("Allow", (dialog, which) -> requestBatteryExemption())
                .setNegativeButton("Not now", null).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putParcelableArrayList("selected", new ArrayList<>(selected));
        ArrayList<Uri> trees = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        int[] counts = new int[sendFolders.size()];
        long[] bytes = new long[sendFolders.size()];
        for (int i = 0; i < sendFolders.size(); i++) {
            SendFolder folder = sendFolders.get(i);
            trees.add(folder.tree);
            names.add(folder.name);
            counts[i] = folder.files;
            bytes[i] = folder.sizeUnknown ? -1 : folder.bytes;
        }
        out.putParcelableArrayList("send_trees", trees);
        out.putStringArrayList("send_tree_names", names);
        out.putIntArray("send_tree_files", counts);
        out.putLongArray("send_tree_bytes", bytes);
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.visible(this);
        try {
            startForegroundService(new Intent(this, ReceiveService.class));
        } catch (RuntimeException error) {
            toast("Receiving could not start. Open HopDrop again to retry.");
        }
        onLiveChanged();
    }

    @Override
    protected void onStop() {
        app.visible(null);
        if (!ReceiveService.background(this)) {
            startService(new Intent(this, ReceiveService.class).setAction(ReceiveService.STOP_WHEN_IDLE));
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        share(intent);
        showTab(intent.getBooleanExtra("show_files", false) ? 2 : 0);
    }

    private void createShell() {
        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.surface);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);

        LinearLayout header = ui.row();
        header.setPadding(ui.dp(20), ui.dp(14), ui.dp(16), ui.dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setContentDescription("HopDrop");
        header.addView(logo, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        LinearLayout title = ui.column();
        title.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        title.addView(ui.text("HopDrop", 20, ui.ink, true));
        phoneName = ui.text("", 13, ui.muted, false);
        ui.singleLine(phoneName);
        title.addView(phoneName);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        statusChip = ui.row();
        statusChip.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        statusDot = ui.dot(ui.success);
        statusChip.addView(statusDot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        status = ui.text("", 13, ui.ink, true);
        status.setPadding(ui.dp(6), 0, 0, 0);
        statusChip.addView(status);
        statusChip.setMinimumHeight(ui.dp(40));
        statusChip.setOnClickListener(v -> explainStatus());
        header.addView(statusChip);
        root.addView(header);

        liveHost = ui.column();
        liveHost.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        root.addView(liveHost);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = ui.column();
        page.setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(ui.divider());
        bottom = ui.row();
        bottom.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(10));
        bottom.setBackgroundColor(ui.card);
        root.addView(bottom);
        updateHeader();
    }

    void updateHeader() {
        if (phoneName == null) return;
        phoneName.setText(app.peer() == null ? "Identity unavailable" : app.peer().name());
        boolean ready = ReceiveService.running;
        status.setText(ready ? "Ready" : "Not receiving");
        int color = ready ? ui.success : ui.muted;
        statusDot.setBackground(ui.shape(color, 5));
        statusChip.setBackground(ui.shape(ready ? ui.successContainer : ui.surfaceAlt, 20));
        statusChip.setContentDescription(ready ? "Ready to receive. Tap for details."
                : "Not receiving. Tap for details.");
    }

    private void explainStatus() {
        List<LocalSockets.Address> addresses = LocalSockets.describe(this);
        StringBuilder text = new StringBuilder();
        if (ReceiveService.running) {
            text.append("Paired devices can send files to this phone").append(
                    ReceiveService.background(this) ? " at any time." : " while HopDrop is open.");
        } else {
            text.append("This phone isn't listening for files right now. Close HopDrop and open it again. ")
                    .append("If it keeps happening, restart Wi-Fi.");
        }
        text.append("\n\n");
        if (addresses.isEmpty()) text.append("No local network. Join a Wi-Fi network or turn on your hotspot.");
        else for (LocalSockets.Address a : addresses) text.append(a.kind).append(": ").append(a.ip).append('\n');
        new AlertDialog.Builder(this).setTitle(ReceiveService.running ? "Ready to receive" : "Not receiving")
                .setMessage(text.toString().trim()).setPositiveButton("OK", null).show();
    }

    private void showTab(int next) {
        if (scroll == null) return;
        scrollY[tab] = scroll.getScrollY();
        int prev = tab;
        tab = next;

        // Animate page content: slide in with crossfade
        View oldPage = page.getChildAt(0);
        entering = true;
        renderPage();
        entering = false;
        if (oldPage != null && page.getChildCount() > 0) {
            ui.slideInContent(page.getChildAt(0));
        }

        bottom.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i;
            boolean active = i == tab;
            boolean wasActive = i == prev;
            LinearLayout item = ui.column();
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setMinimumHeight(ui.dp(56));
            LinearLayout pill = ui.row();
            pill.setGravity(Gravity.CENTER);
            pill.setBackground(ui.shape(active ? ui.primaryContainer : 0, 16));
            pill.addView(ui.icon(TAB_ICONS[i], active ? ui.onPrimaryContainer : ui.muted, null),
                    new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));

            // Animate pill selection
            if (active && !wasActive && Ui.animationsEnabled()) {
                pill.setScaleX(0.8f);
                pill.setScaleY(0.8f);
                android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0.8f, 1f);
                anim.setDuration(150);
                anim.addUpdateListener(a -> {
                    float scale = (float) a.getAnimatedValue();
                    pill.setScaleX(scale);
                    pill.setScaleY(scale);
                });
                anim.start();
            }

            item.addView(pill, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(32)));
            TextView label = ui.text(TABS[i], 12, active ? ui.ink : ui.muted, active);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
            labelParams.topMargin = ui.dp(4);
            item.addView(label, labelParams);
            item.setContentDescription(TABS[i] + " tab" + (active ? ", selected" : ""));
            ui.background(item, ui.card, 16);
            item.setOnClickListener(v -> showTab(destination));
            bottom.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        scroll.post(() -> scroll.scrollTo(0, scrollY[tab]));
        app.setPairingVisible(tab == 1);
        if (tab == 1 && ReceiveService.activeDiscovery != null) ReceiveService.activeDiscovery.query();
    }

    void showDevicesTab() {
        showTab(1);
    }

    private void renderPage() {
        page.removeAllViews();
        if (tab == 0) sendTab.show(page);
        else if (tab == 1) devicesTab.show(page);
        else if (tab == 2) activityTab.show(page);
        else settingsTab.show(page);
    }

    void refreshCurrent() {
        int position = scroll.getScrollY();
        renderPage();
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    public void refresh() {
        updateHeader();
        if (tab == 1 || tab == 2) refreshCurrent();
    }

    public void onPaired() {
        if (qrDialog != null && qrDialog.isShowing()) qrDialog.dismiss();
        updateHeader();
        if (tab != 3) refreshCurrent();
    }

    /** Called (throttled) whenever a transfer moves, the receiver starts/stops, or nearby devices change. */
    void onLiveChanged() {
        updateHeader();
        renderLive();
        if (tab == 0) sendTab.updateProgress();
        else if (tab == 1) devicesTab.refreshLive();
    }

    // ---- Live transfers strip (visible on every tab) ----

    private static final class LiveCard {
        LinearLayout root;
        ImageView badge;
        TextView title;
        TextView detail;
        Ui.Bar bar;
        TextView amount;
        TextView speed;
        ImageView cancel;
        boolean wasFinished = false;
    }

    private void renderLive() {
        List<Live.Item> items = app.live().items();
        Set<String> keep = new HashSet<>();
        for (Live.Item item : items) {
            keep.add(item.key);
            LiveCard card = liveCards.get(item.key);
            if (card == null) {
                card = createLiveCard(item);
                liveCards.put(item.key, card);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.bottomMargin = ui.dp(8);
                liveHost.addView(card.root, params);
                // Animate card slide-in
                ui.slideInUp(card.root);
            }
            bindLiveCard(card, item);
        }
        for (String key : new ArrayList<>(liveCards.keySet())) {
            if (!keep.contains(key)) {
                LiveCard removed = liveCards.remove(key);
                // Animate card slide-out before removing
                ui.slideOutDown(removed.root, () -> {
                    if (liveHost.indexOfChild(removed.root) >= 0) {
                        liveHost.removeView(removed.root);
                    }
                });
            }
        }
        handler.removeCallbacks(pruneLive);
        if (!items.isEmpty()) handler.postDelayed(pruneLive, Live.KEEP_FINISHED_MS + 200);
    }

    /** Removes finished cards once they have been shown long enough, even when nothing else changes. */
    private final Runnable pruneLive = this::renderLive;

    private LiveCard createLiveCard(Live.Item item) {
        LiveCard card = new LiveCard();
        card.root = ui.column();
        card.root.setPadding(ui.dp(14), ui.dp(12), ui.dp(6), ui.dp(12));
        card.root.setBackground(ui.outlined(ui.card, ui.border, 18));
        LinearLayout top = ui.row();
        card.badge = ui.badge(item.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out,
                ui.onPrimaryContainer, ui.primaryContainer, 40, true);
        top.addView(card.badge);
        LinearLayout labels = ui.column();
        labels.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        card.title = ui.text("", 15, ui.ink, true);
        card.title.setMaxLines(2);
        card.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(card.title);
        card.detail = ui.text("", 13, ui.muted, false);
        card.detail.setMaxLines(2);
        labels.addView(card.detail);
        top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        card.cancel = ui.iconButton(R.drawable.ic_action_remove, "Cancel transfer", () -> cancel(item.incoming));
        top.addView(card.cancel);
        card.root.addView(top);
        LinearLayout lower = ui.column();
        lower.setPadding(0, 0, ui.dp(8), 0);
        card.bar = ui.bar(ui.buttonBlue);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, ui.dp(6));
        barParams.topMargin = ui.dp(10);
        lower.addView(card.bar, barParams);
        LinearLayout numbers = ui.row();
        card.amount = ui.text("", 13, ui.ink, false);
        numbers.addView(card.amount, new LinearLayout.LayoutParams(0, -2, 1));
        card.speed = ui.text("", 13, ui.muted, false);
        card.speed.setGravity(Gravity.END);
        numbers.addView(card.speed);
        ui.add(lower, numbers, 6);
        card.root.addView(lower);
        return card;
    }

    private void bindLiveCard(LiveCard card, Live.Item item) {
        Peer.Progress p = item.progress;
        boolean active = !item.finished();
        card.cancel.setVisibility(active ? View.VISIBLE : View.GONE);
        card.bar.setVisibility(active ? View.VISIBLE : View.GONE);
        card.amount.setVisibility(p == null && active ? View.GONE : View.VISIBLE);
        card.speed.setVisibility(active && p != null ? View.VISIBLE : View.GONE);
        if (item.connecting()) {
            card.title.setText("Connecting to " + item.peer + "…");
            card.detail.setText("Getting ready to send " + selectionSummary());
            card.bar.set(-1, ui.buttonBlue);
            return;
        }
        if (p != null && p.reconnecting && !item.finished()) {
            card.title.setText(item.incoming ? "Waiting for " + item.peer + " to reconnect…" : "Reconnecting to " + item.peer + "…");
            card.detail.setText("The connection dropped. HopDrop continues where it stopped.");
            card.amount.setVisibility(View.GONE);
            card.speed.setVisibility(View.GONE);
            card.bar.set(-1, ui.buttonBlue);
            return;
        }
        if (p != null && p.waiting && !item.finished()) {
            card.title.setText("Waiting for " + item.peer + " to accept…");
            card.detail.setText(Format.files(p.count) + " will start sending once they accept.");
            card.amount.setVisibility(View.GONE);
            card.speed.setVisibility(View.GONE);
            card.bar.set(-1, ui.buttonBlue);
            return;
        }
        if (item.failure != null) {
            styleBadge(card, R.drawable.ic_status_error, ui.danger, ui.dangerContainer);
            card.title.setText("Couldn't send to " + item.peer);
            card.detail.setText(item.failure);
            card.amount.setVisibility(View.GONE);
            if (!card.wasFinished) {
                card.wasFinished = true;
                ui.pop(card.badge);
            }
            return;
        }
        if (item.result != null) {
            Peer.Result r = item.result;
            boolean ok = r.error == null;
            styleBadge(card, ok ? R.drawable.ic_action_check : R.drawable.ic_status_error,
                    ok ? ui.success : ui.danger, ok ? ui.successContainer : ui.dangerContainer);
            card.title.setText(ok ? (r.incoming ? "Received " : "Sent ") + Format.files(r.files.size())
                    + (r.incoming ? " from " : " to ") + r.peer
                    : (r.incoming ? "Receiving from " : "Sending to ") + r.peer + " stopped");
            card.detail.setText(ok ? Format.names(r.files) : r.error.startsWith("cancelled")
                    ? Notices.cancelReason(r) : ErrorText.forDevice(r.error, r.peer));
            card.amount.setText(ok ? Format.size(r.bytes) + " in " + Format.duration(Math.max(1, r.millis / 1000))
                    + " · Saved in " + r.folder : r.files.size() + " of " + r.offered + " files done");
            if (!card.wasFinished) {
                card.wasFinished = true;
                ui.pop(card.badge);
            }
            return;
        }
        styleBadge(card, item.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out,
                ui.onPrimaryContainer, ui.primaryContainer);
        card.title.setText((item.incoming ? "Receiving from " : "Sending to ") + item.peer);
        card.detail.setText(p.count == 1 ? p.file : p.file + " · " + p.index + " of " + p.count);
        card.bar.glide(p.total > 0 ? p.done / (float) p.total : -1, ui.buttonBlue);
        card.amount.setText(Format.amount(p.done, p.total) + (p.percent() >= 0 ? "  ·  " + p.percent() + "%" : ""));
        card.speed.setText(Format.speed(p.speed) + (p.secondsLeft >= 0 ? " · " + Format.duration(p.secondsLeft)
                + " left" : ""));
        card.wasFinished = false;
    }

    private void styleBadge(LiveCard card, int icon, int fg, int bg) {
        card.badge.setImageResource(icon);
        card.badge.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
        card.badge.setBackground(ui.shape(bg, 20));
    }

    void cancel(boolean incoming) {
        startService(new Intent(this, incoming ? ReceiveService.class : TransferService.class).setAction("cancel"));
    }

    boolean online(String id) {
        Discovery discovery = ReceiveService.activeDiscovery;
        if (discovery == null) return false;
        for (Discovery.Nearby item : discovery.list()) {
            if (item.id.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    int currentTab() {
        return tab;
    }

    void setThemeMode(int mode) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("theme", mode).apply();
        recreate();
    }

    void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, FILES);
    }

    /** Nothing added to send yet. */
    boolean nothingSelected() {
        return selected.isEmpty() && sendFolders.isEmpty();
    }

    /** "3 files", "1 folder", "2 folders and 3 files". */
    String selectionSummary() {
        if (sendFolders.isEmpty()) return Format.files(selected.size());
        String folders = Format.folders(sendFolders.size());
        return selected.isEmpty() ? folders : folders + " and " + Format.files(selected.size());
    }

    void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, SEND_FOLDER);
    }

    /** Adds a picked folder as one item, then counts its files and size in the background. */
    private void addFolder(Uri tree) {
        for (SendFolder existing : sendFolders) {
            if (existing.tree.equals(tree)) {
                toast("That folder is already in the list.");
                return;
            }
        }
        SendFolder folder = new SendFolder(tree, "Folder");
        sendFolders.add(folder);
        showTab(0);
        work.execute(() -> {
            try {
                String name = FolderScan.name(getContentResolver(), tree);
                List<FolderScan.Item> files = FolderScan.files(getContentResolver(), tree);
                long bytes = 0;
                boolean unknown = false;
                for (FolderScan.Item file : files) {
                    if (file.size < 0) unknown = true;
                    else bytes += file.size;
                }
                long total = bytes;
                boolean sizeUnknown = unknown;
                runOnUiThread(() -> {
                    folder.name = name;
                    folder.files = files.size();
                    folder.bytes = total;
                    folder.sizeUnknown = sizeUnknown;
                    if (files.isEmpty()) {
                        sendFolders.remove(folder);
                        toast("That folder has no files to send.");
                    }
                    if (tab == 0) refreshCurrent();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    sendFolders.remove(folder);
                    toast("HopDrop couldn't read that folder.");
                    if (tab == 0) refreshCurrent();
                });
            }
        });
    }

    void pickPhotos() {
        boolean picker = Build.VERSION.SDK_INT >= 33 || Build.VERSION.SDK_INT >= 30
                && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
        Intent intent = picker ? new Intent(MediaStore.ACTION_PICK_IMAGES) : new Intent(Intent.ACTION_GET_CONTENT);
        if (picker) {
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(100, MediaStore.getPickImagesMaxLimit()));
        } else {
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(intent, PHOTOS);
    }

    void send(Peer.Device device) {
        if (nothingSelected()) {
            toast("Add files first.");
            return;
        }
        Live.Item current = app.live().outgoing(device.label());
        if (current != null && !current.finished()) {
            toast("Already sending to " + device.label() + ".");
            return;
        }
        Intent intent = new Intent(this, TransferService.class);
        intent.putExtra("device", device.id);
        // Folders go as their folder address only; the service lists their files when it sends them.
        ArrayList<Uri> trees = new ArrayList<>();
        for (SendFolder folder : sendFolders) trees.add(folder.tree);
        intent.putParcelableArrayListExtra("files", new ArrayList<>(selected));
        intent.putParcelableArrayListExtra("trees", trees);
        intent.putExtra("summary", selectionSummary());
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ArrayList<Uri> all = new ArrayList<>(selected);
        all.addAll(trees);
        ClipData clip = ClipData.newRawUri("HopDrop files", all.get(0));
        for (int i = 1; i < all.size(); i++) clip.addItem(new ClipData.Item(all.get(i)));
        intent.setClipData(clip);
        app.live().connecting(device.label());
        try {
            startForegroundService(intent);
        } catch (RuntimeException error) {
            app.live().failedToStart(device.label(), ErrorText.forDevice(error, device.label()));
        }
    }

    void rename(Peer.Device device) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(device.label());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Rename device").setView(padded(input))
                .setNegativeButton("Cancel", null).setPositiveButton("Save", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() > 40) {
                        toast("Use at most 40 characters.");
                        return;
                    }
                    device.alias = value.isEmpty() ? null : value;
                    app.book().put(device);
                    refreshCurrent();
                }).show();
    }

    void remove(Peer.Device device) {
        new AlertDialog.Builder(this).setTitle("Remove " + device.label() + "?")
                .setMessage("You'll need to pair again to send files between these devices. "
                        + "HopDrop also tells " + device.label() + " to forget this phone if it's reachable.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> work.execute(() -> {
                    app.peer().unpair(device);
                    runOnUiThread(this::refreshCurrent);
                })).show();
    }

    View padded(View view) {
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(view);
        return box;
    }

    void addIp() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint("192.168.1.23");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout body = ui.column();
        body.addView(input);
        TextView hint = ui.text("Find it on the other device: HopDrop → Devices → This computer / This phone.",
                13, ui.muted, false);
        ui.add(body, hint, 4);
        new AlertDialog.Builder(this).setTitle("Pair by IP address").setView(padded(body))
                .setNegativeButton("Cancel", null).setPositiveButton("Pair", (dialog, which) -> {
                    String[] parts = input.getText().toString().trim().split(":", 2);
                    try {
                        if (!com.hop.drop.core.PairUri.validIpv4(parts[0])) throw new IllegalArgumentException();
                        int port = parts.length == 2 ? Integer.parseInt(parts[1]) : 7410;
                        if (port < 1 || port > 65535) throw new IllegalArgumentException();
                        pairSas(parts[0], port);
                    } catch (Exception error) {
                        toast("Enter an address like 192.168.1.23");
                    }
                }).show();
    }

    void pairSas(String address, int port) {
        toast("Connecting to " + address + "…");
        work.execute(() -> {
            try {
                Peer.Device device = app.peer().pairSas(address, port);
                runOnUiThread(() -> {
                    toast("Paired with " + device.name);
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> toast(ErrorText.forDevice(error, "The device")));
            }
        });
    }

    public void showPrompt(HopApp.Prompt prompt) {
        if (shown == prompt) return;
        shown = prompt;
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(12));
        TextView code = ui.text(prompt.code, 38, ui.blue, true);
        code.setGravity(Gravity.CENTER);
        code.setLetterSpacing(.08f);
        ui.add(body, code, 8);
        TextView explanation = ui.text("Does " + prompt.name + " show the same number?", 15, ui.ink, false);
        explanation.setGravity(Gravity.CENTER);
        ui.add(body, explanation, 12);
        promptDialog = new AlertDialog.Builder(this).setTitle("Pair with " + prompt.name).setView(body)
                .setPositiveButton("They match", (dialog, which) -> prompt.answer(true))
                .setNegativeButton("Cancel", (dialog, which) -> prompt.answer(false))
                .setOnCancelListener(dialog -> prompt.answer(false)).create();
        promptDialog.setOnDismissListener(dialog -> {
            if (shown == prompt) shown = null;
            promptDialog = null;
        });
        promptDialog.show();
        // Animate content
        if (Ui.animationsEnabled()) {
            body.setScaleX(0.9f);
            body.setScaleY(0.9f);
            body.setAlpha(0f);
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(200);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float fraction = (float) a.getAnimatedValue();
                body.setScaleX(0.9f + 0.1f * fraction);
                body.setScaleY(0.9f + 0.1f * fraction);
                body.setAlpha(fraction);
            });
            anim.start();
        }
    }

    /** "Ask before receiving" while HopDrop is open. */
    public void showOffer(Peer.Offer offer) {
        if (offerDialogs.containsKey(offer.transferId)) return;
        String size = offer.total >= 0 ? " (" + Format.size(offer.total) + ")" : "";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(offer.peer + " wants to send you " + Format.files(offer.files.size()))
                .setMessage(Format.names(offer.files) + size + "\n\nAccept to save them in your receive folder.")
                .setPositiveButton("Accept", (d, which) -> offer.answer(true))
                .setNegativeButton("Decline", (d, which) -> offer.answer(false))
                .setOnCancelListener(d -> offer.answer(false)).create();
        dialog.setOnDismissListener(d -> offerDialogs.remove(offer.transferId));
        offerDialogs.put(offer.transferId, dialog);
        dialog.show();
        // Animate dialog
        if (Ui.animationsEnabled()) {
            android.view.View content = dialog.getWindow().getDecorView();
            content.setScaleX(0.9f);
            content.setScaleY(0.9f);
            content.setAlpha(0f);
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(200);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float fraction = (float) a.getAnimatedValue();
                content.setScaleX(0.9f + 0.1f * fraction);
                content.setScaleY(0.9f + 0.1f * fraction);
                content.setAlpha(fraction);
            });
            anim.start();
        }
    }

    public void closeOffer(Peer.Offer offer) {
        AlertDialog dialog = offerDialogs.remove(offer.transferId);
        if (dialog != null) dialog.dismiss();
    }

    public void closePrompt(HopApp.Prompt prompt, String reason) {
        if (shown != prompt) return;
        if (promptDialog != null) promptDialog.dismiss();
        shown = null;
        toast(reason);
    }

    void showQr() {
        List<String> addresses = LocalSockets.addresses(this);
        if (addresses.isEmpty()) {
            toast("Join a Wi-Fi network or turn on your hotspot first.");
            return;
        }
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("HopDrop pairing QR code");
        image.setBackground(ui.shape(Color.WHITE, 16));
        image.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
        body.addView(image, new LinearLayout.LayoutParams(-1, ui.dp(280)));
        StringBuilder where = new StringBuilder("Scan with HopDrop on the other device. Works once, for 5 minutes.\n");
        for (LocalSockets.Address a : LocalSockets.describe(this)) {
            where.append('\n').append(a.kind).append(": ").append(a.ip);
        }
        ui.add(body, ui.text(where.toString(), 14, ui.muted, false), 12);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("My pairing QR code").setView(body)
                .setPositiveButton("Done", null).create();
        qrDialog = dialog;
        dialog.setOnDismissListener(which -> {
            app.peer().invalidateQr();
            qrDialog = null;
        });
        Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                try {
                    String uri = app.peer().issueQr(addresses, 7410);
                    // Medium error correction keeps the code less dense than the default with room for glare;
                    // one pixel per module, scaled up without smoothing, keeps every module sharp.
                    Map<EncodeHintType, Object> hints = new HashMap<>();
                    hints.put(EncodeHintType.MARGIN, 2);
                    hints.put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
                    BitMatrix matrix = new MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0, hints);
                    int side = matrix.getWidth();
                    int[] pixels = new int[side * side];
                    for (int y = 0; y < side; y++) {
                        for (int x = 0; x < side; x++) pixels[y * side + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                    }
                    android.graphics.drawable.BitmapDrawable sharp = new android.graphics.drawable.BitmapDrawable(
                            getResources(), Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888));
                    sharp.setFilterBitmap(false);
                    image.setImageDrawable(sharp);
                    handler.postDelayed(this, 300000);
                } catch (Exception error) {
                    toast("Couldn't show a QR code. Try again.");
                }
            }
        };
        dialog.show();
        // Animate content
        if (Ui.animationsEnabled()) {
            body.setScaleX(0.9f);
            body.setScaleY(0.9f);
            body.setAlpha(0f);
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(200);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float fraction = (float) a.getAnimatedValue();
                body.setScaleX(0.9f + 0.1f * fraction);
                body.setScaleY(0.9f + 0.1f * fraction);
                body.setAlpha(fraction);
            });
            anim.start();
        }
        refresh.run();
    }

    void copy(String label, String value) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, value));
        toast("Copied " + value);
    }

    void scanQr() {
        startActivityForResult(new Intent(this, QrScanActivity.class), SCAN);
    }

    void bluetooth() {
        if (nothingSelected()) {
            toast("Add files first.");
            return;
        }
        if (!sendFolders.isEmpty()) {
            // Bluetooth has no folders: it sends the files inside them, side by side.
            toast("Bluetooth can't keep folders: it sends the files inside them, without their subfolders.");
            List<Uri> trees = new ArrayList<>();
            for (SendFolder folder : sendFolders) trees.add(folder.tree);
            work.execute(() -> {
                ArrayList<Uri> files = new ArrayList<>(selected);
                try {
                    for (Uri tree : trees) {
                        for (FolderScan.Item item : FolderScan.files(getContentResolver(), tree)) files.add(item.uri);
                    }
                } catch (Exception error) {
                    runOnUiThread(() -> toast("HopDrop couldn't read that folder."));
                    return;
                }
                runOnUiThread(() -> bluetooth(files));
            });
            return;
        }
        bluetooth(new ArrayList<>(selected));
    }

    private void bluetooth(ArrayList<Uri> files) {
        if (files.isEmpty()) {
            toast("There are no files to send.");
            return;
        }
        Runnable launch = () -> {
            Intent share = new Intent(files.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            share.setType("*/*");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clip = ClipData.newRawUri("HopDrop files", files.get(0));
            for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
            share.setClipData(clip);
            if (files.size() == 1) share.putExtra(Intent.EXTRA_STREAM, files.get(0));
            else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
            ResolveInfo target = null;
            for (ResolveInfo candidate : getPackageManager().queryIntentActivities(share, 0)) {
                if (candidate.activityInfo.packageName.toLowerCase(java.util.Locale.ROOT).contains("bluetooth")) {
                    target = candidate;
                    break;
                }
            }
            try {
                if (target != null) {
                    share.setClassName(target.activityInfo.packageName, target.activityInfo.name);
                    startActivity(share);
                } else startActivity(Intent.createChooser(share, "Send with Bluetooth"));
            } catch (Exception error) {
                toast("Bluetooth sharing isn't available on this phone.");
            }
        };
        new AlertDialog.Builder(this).setTitle("Send with Bluetooth")
                .setMessage("1. On the laptop, open HopDrop and click Receive via Bluetooth. "
                        + "It waits for your phone and saves the files in its receive folder.\n"
                        + "2. Here, pick the laptop in Android's Bluetooth list.\n\n"
                        + "Bluetooth is much slower than Wi-Fi. Use it when there's no shared network.")
                .setPositiveButton("Continue", (dialog, which) -> launch.run())
                .setNegativeButton("Cancel", null).show();
    }

    boolean batteryRestricted() {
        PowerManager power = getSystemService(PowerManager.class);
        return power != null && !power.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Asks Android to let HopDrop keep receiving when the phone is idle. */
    void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            openAppSettings();
        }
    }

    void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            toast("Open Settings → Apps → HopDrop → Battery and allow background activity.");
        }
    }

    private void share(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        collect(intent);
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && intent.getParcelableExtra(Intent.EXTRA_STREAM) == null) {
            try {
                selected.add(SharedTextProvider.create(this, text));
            } catch (Exception error) {
                toast("Couldn't prepare the shared text.");
            }
        }
    }

    private void collect(Intent intent) {
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) addUri(clip.getItemAt(i).getUri());
        }
        ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (many != null) for (Uri uri : many) addUri(uri);
        if (!Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            addUri(intent.getParcelableExtra(Intent.EXTRA_STREAM));
        }
        addUri(intent.getData());
    }

    private void addUri(Uri uri) {
        if (uri != null && !selected.contains(uri)) selected.add(uri);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) return;
        if (request == FILES || request == PHOTOS) {
            int before = selected.size();
            collect(data);
            if (request == FILES) {
                for (int i = before; i < selected.size(); i++) {
                    try {
                        getContentResolver().takePersistableUriPermission(selected.get(i),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {
                    }
                }
            }
            showTab(0);
        } else if (request == SEND_FOLDER) {
            Uri tree = data.getData();
            if (tree != null) {
                try {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                addFolder(tree);
            }
        } else if (request == TREE) {
            Uri uri = data.getData();
            if (uri != null) {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("folder", uri.toString()).apply();
                refreshCurrent();
            }
        } else if (request == SCAN) {
            if (data.getBooleanExtra("fallback", false)) {
                toast("Tap the other device under Nearby devices, then check that both screens show the same 6-digit number.");
                return;
            }
            String uri = data.getStringExtra("uri");
            if (uri != null) pairScanned(uri);
        }
    }

    /** Pairs with the device from a scanned QR code, showing progress until it succeeds or explains why not. */
    private void pairScanned(String uri) {
        String name;
        try {
            name = com.hop.drop.core.PairUri.parse(uri).name;
        } catch (IllegalArgumentException error) {
            toast("That QR code can't be used. Show a new one and scan it again.");
            return;
        }
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(8));
        ui.add(body, ui.text("Connecting securely to " + name + "…", 15, ui.muted, false), 0);
        Ui.Bar bar = ui.bar(ui.buttonBlue);
        bar.set(-1, ui.buttonBlue);
        ui.add(body, bar, 16);
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Pairing with " + name).setView(body)
                .setCancelable(false).show();
        work.execute(() -> {
            try {
                Peer.Device device = app.peer().pairQr(uri);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    progress.dismiss();
                    toast("Paired with " + device.name + ". You can send files both ways now.");
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    progress.dismiss();
                    new AlertDialog.Builder(this).setTitle("Couldn't pair with " + name)
                            .setMessage(ErrorText.forDevice(error, name))
                            .setPositiveButton("Scan again", (dialog, which) -> scanQr())
                            .setNegativeButton("Close", null).show();
                });
            }
        });
    }

    void chooseFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, TREE);
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
