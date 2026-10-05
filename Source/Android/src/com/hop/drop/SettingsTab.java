package com.hop.drop;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

final class SettingsTab {
    private final MainActivity activity;
    private final Ui ui;
    private String nameDraft;

    SettingsTab(MainActivity activity) {
        this.activity = activity;
        ui = activity.ui;
    }

    void show(LinearLayout page) {
        SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, 0);
        showAppearance(page, prefs);
        showIdentity(page);
        showFolder(page, prefs);
        showReceiving(page, prefs);
        showPrivacy(page, prefs);
        showAbout(page);
    }

    private void showAppearance(LinearLayout page, SharedPreferences prefs) {
        LinearLayout card = ui.card(page, "Appearance");
        ui.add(card, ui.text("Choose a theme, or follow the phone's setting.", 14, ui.muted, false), 2);
        int mode = prefs.getInt("theme", 0);
        ui.add(card, ui.segmented(new String[]{"System", "Light", "Dark"}, mode, choice -> {
            if (choice != mode) activity.setThemeMode(choice);
        }), 12);
    }

    private void showIdentity(LinearLayout page) {
        LinearLayout identity = ui.card(page, "This phone's name");
        ui.add(identity, ui.text("Other devices see this name when they look for HopDrop.", 14, ui.muted, false), 2);
        EditText name = new EditText(activity);
        name.setSingleLine();
        name.setText(nameDraft == null ? activity.app.peer().name() : nameDraft);
        name.setTextSize(16);
        name.setTextColor(ui.ink);
        name.setHintTextColor(ui.muted);
        name.setMinHeight(ui.dp(52));
        name.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        name.setBackground(ui.outlined(ui.surfaceAlt, ui.border, 14));
        name.setContentDescription("This phone's name");
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                nameDraft = text.toString();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        ui.add(identity, name, 12);
        ui.add(identity, ui.button("Save name", R.drawable.ic_action_check, () -> {
            String value = name.getText().toString().trim();
            if (value.length() < 1 || value.length() > 40) {
                name.setError("Use 1–40 characters");
                return;
            }
            activity.app.peer().setName(value);
            activity.getSharedPreferences(MainActivity.PREFS, 0).edit().putString("name", value).apply();
            nameDraft = null;
            activity.updateHeader();
            activity.app.notices().showReady();
            if (ReceiveService.activeDiscovery != null) ReceiveService.activeDiscovery.announce();
            activity.toast("Name saved.");
        }, Ui.TONAL), 10);
    }

    private void showFolder(LinearLayout page, SharedPreferences prefs) {
        LinearLayout folder = ui.card(page, "Receive folder");
        ui.add(folder, ui.text("Files other devices send to this phone are saved here.", 14, ui.muted, false), 2);
        ImageView icon = ui.badge(R.drawable.ic_action_folder, ui.onAmberContainer, ui.amberContainer, 44, false);
        boolean custom = prefs.getString("folder", null) != null;
        ui.add(folder, ui.listRow(icon, folderName(prefs), custom ? "Folder you chose" : "Default folder", null, null), 12);
        LinearLayout change = ui.button("Change", R.drawable.ic_action_folder, activity::chooseFolder, Ui.TONAL);
        if (custom) {
            ui.add(folder, ui.buttons(change, ui.button("Use default", R.drawable.ic_action_refresh, () -> {
                prefs.edit().remove("folder").apply();
                activity.refreshCurrent();
            }, Ui.OUTLINE)), 12);
        } else {
            ui.add(folder, change, 12);
        }
    }

    private String folderName(SharedPreferences prefs) {
        String saved = prefs.getString("folder", null);
        if (saved == null) return "Download/HopDrop";
        try {
            Uri tree = Uri.parse(saved);
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = activity.getContentResolver().query(document,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getString(0);
                }
            }
            String id = DocumentsContract.getTreeDocumentId(tree);
            int separator = id.lastIndexOf(':');
            String path = separator < 0 ? id : id.substring(separator + 1);
            int slash = path.lastIndexOf('/');
            if (!path.isEmpty()) return slash < 0 ? path : path.substring(slash + 1);
        } catch (Exception ignored) {
        }
        return "Selected folder";
    }

    private void showReceiving(LinearLayout page, SharedPreferences prefs) {
        LinearLayout receiving = ui.card(page, "Receiving");
        boolean background = ReceiveService.background(activity);
        LinearLayout line = ui.row();
        line.setMinimumHeight(ui.dp(56));
        LinearLayout labels = ui.column();
        labels.addView(ui.text("Receive in the background", 16, ui.ink, true));
        TextView sub = ui.text(background ? "On: paired devices can send any time, even with HopDrop closed."
                : "Off: this phone receives only while HopDrop is open.", 14, ui.muted, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = ui.dp(2);
        labels.addView(sub, subParams);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity);
        toggle.setShowText(false);
        toggle.setChecked(background);
        toggle.setMinHeight(ui.dp(48));
        toggle.setContentDescription("Receive in the background");
        line.addView(toggle);
        ui.add(receiving, line, 8);
        ui.add(receiving, ui.text("Uses a small ongoing notification and some battery.", 13, ui.muted, false), 4);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putBoolean("background", enabled).apply();
            try {
                if (enabled) {
                    activity.startForegroundService(new Intent(activity, ReceiveService.class));
                    if (activity.batteryRestricted()) activity.requestBatteryExemption();
                }
            } catch (RuntimeException error) {
                activity.toast("Receiving could not start. Open HopDrop again to retry.");
            }
            activity.refreshCurrent();
        });
        if (background && activity.batteryRestricted()) {
            LinearLayout warning = ui.column();
            warning.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            warning.setBackground(ui.shape(ui.amberContainer, 14));
            warning.addView(ui.text("Android may pause HopDrop to save battery", 14, ui.onAmberContainer, true));
            TextView detail = ui.text("Allow HopDrop to run in the background so files still arrive when the "
                    + "screen is off.", 13, ui.onAmberContainer, false);
            warning.addView(detail);
            ui.add(warning, ui.button("Allow background activity", R.drawable.ic_battery,
                    activity::requestBatteryExemption, Ui.OUTLINE), 10);
            ui.add(receiving, warning, 12);
        }
        Switch ask = switchRow(receiving, "Ask before receiving",
                "Accept or decline files first. Devices you trust (Devices tab) send without asking.",
                prefs.getBoolean("ask", false));
        ask.setOnCheckedChangeListener((button, on) -> {
            prefs.edit().putBoolean("ask", on).apply();
            if (activity.app.peer() != null) activity.app.peer().askBeforeReceiving = on;
        });
        if (background) {
            ui.add(receiving, ui.text("On Realme, Oppo, Xiaomi and similar phones, also turn on Auto-launch "
                    + "(or \"Allow background activity\") for HopDrop in the phone's app settings.", 13, ui.muted, false), 10);
            ui.add(receiving, ui.button("Open HopDrop's app settings", R.drawable.ic_tab_settings,
                    activity::openAppSettings, Ui.OUTLINE), 8);
        }
    }

    private Switch switchRow(LinearLayout card, String title, String subtitle, boolean checked) {
        LinearLayout line = ui.row();
        line.setMinimumHeight(ui.dp(56));
        LinearLayout labels = ui.column();
        labels.addView(ui.text(title, 16, ui.ink, true));
        TextView sub = ui.text(subtitle, 14, ui.muted, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = ui.dp(2);
        labels.addView(sub, subParams);
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(activity);
        toggle.setShowText(false);
        toggle.setChecked(checked);
        toggle.setMinHeight(ui.dp(48));
        toggle.setContentDescription(title);
        line.addView(toggle);
        ui.add(card, line, 12);
        return toggle;
    }

    private void showPrivacy(LinearLayout page, SharedPreferences prefs) {
        LinearLayout privacy = ui.card(page, "Privacy");
        ui.add(privacy, ui.text("Who can see this phone's name on the network. Paired devices always find it.",
                14, ui.muted, false), 2);
        int mode = "paired".equals(prefs.getString("visibility", "everyone")) ? 1 : 0;
        ui.add(privacy, ui.segmented(new String[]{"Everyone", "Paired only"}, mode, choice -> {
            prefs.edit().putString("visibility", choice == 1 ? "paired" : "everyone").apply();
            activity.app.setPairingVisible(activity.currentTab() == 1);
            activity.refreshCurrent();
        }), 12);
        ui.add(privacy, ui.text(mode == 1 ? "Hidden from devices you haven't paired, except while the Devices tab is open."
                : "Any device on the same Wi-Fi or hotspot can see this phone's name.", 13, ui.muted, false), 10);
    }

    private void showAbout(LinearLayout page) {
        LinearLayout about = ui.card(page, "About");
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(activity);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setContentDescription("HopDrop logo");
        line.addView(logo, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        LinearLayout labels = ui.column();
        labels.setPadding(ui.dp(12), 0, 0, 0);
        labels.addView(ui.text("HopDrop " + version(), 16, ui.ink, true));
        labels.addView(ui.text("Private transfers on your own network. No cloud, no accounts.", 13, ui.muted, false));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ui.add(about, line, 12);
        String id = activity.app.peer() == null ? "Unavailable"
                : activity.app.peer().id().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        ui.add(about, ui.text("Device ID  " + id.substring(0, Math.min(4, id.length())) + " "
                + (id.length() > 4 ? id.substring(4) : ""), 13, ui.muted, false), 12);
    }

    private String version() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "";
        }
    }
}
