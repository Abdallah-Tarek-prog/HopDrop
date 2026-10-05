package com.hop.drop;

import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import com.hop.drop.core.Peer;
import com.hop.drop.net.Discovery;
import com.hop.drop.net.LocalSockets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

final class DevicesTab {
    private final MainActivity activity;
    private final Ui ui;
    private LinearLayout pairedHost;
    private LinearLayout nearbyHost;
    private String shownState = "";
    private boolean firstFill = false;
    private int animIndex = 0;

    DevicesTab(MainActivity activity) {
        this.activity = activity;
        ui = activity.ui;
    }

    void show(LinearLayout page) {
        showThisPhone(page);
        LinearLayout paired = ui.card(page, "Paired devices");
        pairedHost = ui.column();
        paired.addView(pairedHost);
        ui.add(paired, ui.text("Pairing is permanent: paired devices stay paired until you remove them "
                + "here or on the other device.", 13, ui.muted, false), 10);
        showPairing(page);
        LinearLayout nearby = ui.card(page, "Nearby devices", "Refresh", () -> {
            Discovery discovery = ReceiveService.activeDiscovery;
            if (discovery != null) discovery.query();
            activity.toast("Looking for devices…");
        });
        nearbyHost = ui.column();
        nearby.addView(nearbyHost);
        shownState = "";
        firstFill = true;
        animIndex = 0;
        refreshLive();
    }

    private void showThisPhone(LinearLayout page) {
        LinearLayout card = ui.card(page, "This phone");
        List<LocalSockets.Address> addresses = LocalSockets.describe(activity);
        String name = activity.app.peer() == null ? "Unavailable" : activity.app.peer().name();
        ui.add(card, ui.text(name + (ReceiveService.running ? " · visible to nearby devices" : " · not receiving"),
                14, ui.muted, false), 2);
        if (addresses.isEmpty()) {
            ui.add(card, ui.text("Not on a local network. Join a Wi-Fi network or turn on your hotspot.",
                    14, ui.danger, false), 10);
        }
        for (LocalSockets.Address address : addresses) {
            int icon = address.kind.equals("Hotspot") ? R.drawable.ic_hotspot : R.drawable.ic_wifi;
            ImageView badge = ui.badge(icon, ui.onPrimaryContainer, ui.primaryContainer, 40, true);
            ImageView copy = ui.iconButton(R.drawable.ic_copy, "Copy " + address.ip,
                    () -> activity.copy("IP address", address.ip));
            LinearLayout row = ui.listRow(badge, address.ip, address.kind + " · port 7410", copy,
                    () -> activity.copy("IP address", address.ip));
            ui.add(card, row, 8);
        }
        ui.add(card, ui.text("Pairing by IP address? Type one of these on the other device.", 13, ui.muted, false), 10);
        ui.add(card, ui.button("Show my QR code", R.drawable.ic_action_qr_show, activity::showQr, Ui.OUTLINE), 12);
    }

    private void showPairing(LinearLayout page) {
        LinearLayout pair = ui.card(page, "Pair a new device");
        ui.add(pair, ui.text("Pair once. After that, files go straight through, both ways, with no codes.",
                14, ui.muted, false), 2);
        ui.add(pair, ui.buttons(
                ui.button("Scan QR", R.drawable.ic_action_qr_scan, activity::scanQr, Ui.PRIMARY),
                ui.button("Pair by IP", R.drawable.ic_action_add, activity::addIp, Ui.TONAL)), 14);
        ui.add(pair, ui.text("Or tap a device under Nearby devices and compare the 6-digit number.",
                13, ui.muted, false), 10);
    }

    /** Redraws paired/nearby lists when discovery or the receiver changes, without rebuilding the page. */
    void refreshLive() {
        if (pairedHost == null || nearbyHost == null || activity.currentTab() != 1) return;
        Discovery discovery = ReceiveService.activeDiscovery;
        List<Discovery.Nearby> nearby = discovery == null ? java.util.Collections.emptyList() : discovery.list();
        List<Peer.Device> devices = activity.app.book().all();
        StringBuilder state = new StringBuilder(ReceiveService.running + "|");
        for (Discovery.Nearby n : nearby) state.append(n.id).append(n.address).append(n.name).append(';');
        for (Peer.Device d : devices) state.append(d.id).append(d.label()).append(',');
        if (state.toString().equals(shownState)) return;
        shownState = state.toString();
        fillPaired(devices);
        fillNearby(nearby);
    }

    private void fillPaired(List<Peer.Device> devices) {
        pairedHost.removeAllViews();
        if (devices.isEmpty()) {
            ui.empty(pairedHost, R.drawable.ic_tab_devices, "No paired devices yet",
                    "Scan a QR code, pair by IP, or tap a nearby device below.");
            return;
        }
        DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (Peer.Device device : devices) {
            boolean windows = "windows".equals(device.platform);
            boolean online = activity.online(device.id);
            ImageView badge = ui.badge(windows ? R.drawable.ic_platform_laptop : R.drawable.ic_platform_phone,
                    ui.onPrimaryContainer, ui.primaryContainer, 44, true);
            String sub = (windows ? "Windows" : "Android") + " · paired " + date.format(new Date(device.pairedAt));
            ImageView more = ui.icon(R.drawable.ic_chevron_right, ui.muted, null);
            LinearLayout row = ui.listRow(badge, device.label(), sub, more, () -> showActions(device));
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = ui.dp(4);
            labels.addView(ui.status(online ? "Online now" : SendTab.lastSeen(device.lastSeen),
                    online ? ui.success : ui.muted), params);
            row.setContentDescription(device.label() + ", " + (online ? "online" : "offline") + ". Tap for options.");
            ui.add(pairedHost, row, 8);
            if (firstFill) animateListItem(row);
        }
    }

    private void fillNearby(List<Discovery.Nearby> nearby) {
        nearbyHost.removeAllViews();
        if (!ReceiveService.running) {
            ui.empty(nearbyHost, R.drawable.ic_status_error, "Not searching",
                    "HopDrop isn't receiving right now, so it can't see other devices. Close HopDrop and open it again.");
            if (firstFill) firstFill = false;
            return;
        }
        if (LocalSockets.describe(activity).isEmpty()) {
            ui.empty(nearbyHost, R.drawable.ic_wifi, "No local network",
                    "Join the same Wi-Fi as the other device, or turn on your hotspot and let it join.");
            if (firstFill) firstFill = false;
            return;
        }
        if (nearby.isEmpty()) {
            ui.empty(nearbyHost, R.drawable.ic_action_refresh, "Searching…",
                    "Open HopDrop on the other device. Both must be on the same Wi-Fi or hotspot.");
            if (firstFill) firstFill = false;
            return;
        }
        for (Discovery.Nearby device : nearby) {
            Peer.Device known = activity.app.book().get(device.id);
            boolean paired = known != null;
            // A device that hides its name only shows here once paired (it shows its name while its own pairing screen is open).
            if (device.name.isEmpty() && !paired) continue;
            String name = device.name.isEmpty() ? known.label() : device.name;
            boolean windows = "windows".equals(device.platform);
            ImageView badge = ui.badge(windows ? R.drawable.ic_platform_laptop : R.drawable.ic_platform_phone,
                    paired ? ui.success : ui.onPrimaryContainer, paired ? ui.successContainer : ui.primaryContainer,
                    44, true);
            TextView chip = paired ? ui.chip("Paired", ui.success, ui.successContainer)
                    : ui.chip("Pair", ui.white, ui.buttonBlue);
            String sub = (paired ? "Ready to send · " : "Tap to pair · ") + device.address;
            LinearLayout row = ui.listRow(badge, name, sub, chip, paired
                    ? () -> activity.toast(name + " is already paired. Send from the Send tab.")
                    : () -> activity.pairSas(device.address, device.port));
            row.setContentDescription((paired ? "" : "Pair with ") + name + (paired ? ", already paired" : ""));
            ui.add(nearbyHost, row, 8);
            if (firstFill) animateListItem(row);
        }
        if (firstFill) firstFill = false;
    }

    private void animateListItem(android.view.View row) {
        if (activity.entering && animIndex < 12 && Ui.animationsEnabled()) {
            row.setAlpha(0f);
            activity.handler.postDelayed(() -> ui.fadeIn(row), animIndex * 20);
        }
        animIndex++;
    }

    private void showActions(Peer.Device device) {
        String addresses = device.addresses.isEmpty() ? "" : "\nLast address: " + device.addresses.get(0);
        LinearLayout body = ui.column();
        body.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        body.addView(ui.text("Paired since " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(device.pairedAt)) + addresses, 14, ui.muted, false));
        Switch trust = new Switch(activity);
        trust.setText("Trust this device");
        trust.setTextSize(16);
        trust.setTextColor(ui.ink);
        trust.setMinHeight(ui.dp(48));
        trust.setChecked(device.trusted);
        trust.setOnCheckedChangeListener((button, on) -> activity.app.peer().setTrusted(device.id, on));
        ui.add(body, trust, 12);
        ui.add(body, ui.text("Trusted devices send without asking, even when \"Ask before receiving\" is on.",
                13, ui.muted, false), 2);
        new android.app.AlertDialog.Builder(activity).setTitle(device.label()).setView(body)
                .setPositiveButton("Rename", (dialog, which) -> activity.rename(device))
                .setNegativeButton("Remove", (dialog, which) -> activity.remove(device))
                .setNeutralButton("Close", null).show();
    }
}
