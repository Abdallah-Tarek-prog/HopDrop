package com.hop.drop.ui

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws HopDrop's screens with sample data, in light and dark and in each colour theme, into
 * Source/Android/screenshots/ (./gradlew recordRoborazziDebug). Used to review the design without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class ScreenshotTests {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HopUiState, mode: ThemeMode = ThemeMode.Light, palette: Palette = Palette.Ember) {
        compose.setContent { HopDropTheme(mode, palette) { HopDropApp(state, NoActions) } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")
    private fun screen(name: String) = captureScreenRoboImage("screenshots/$name.png")

    @Test fun sendEmptyLight() { show(Sample.state()); shot("send-empty-light") }
    @Test fun sendEmptyDark() { show(Sample.state(), ThemeMode.Dark); shot("send-empty-dark") }
    @Test fun sendSelectedLight() { show(Sample.state(selected = true, sending = true)); shot("send-selected-light") }
    @Test fun sendSelectedDark() { show(Sample.state(selected = true, sending = true), ThemeMode.Dark); shot("send-selected-dark") }
    @Test fun sendFirstRun() { show(Sample.state(devices = false)); shot("send-first-run-light") }

    @Test fun activityLight() { show(Sample.state(tab = Tab.Activity)); shot("activity-light") }
    @Test fun activityDark() { show(Sample.state(tab = Tab.Activity), ThemeMode.Dark); shot("activity-dark") }
    @Test fun activityExpanded() {
        show(Sample.state(tab = Tab.Activity))
        compose.onNodeWithText("From LOQ Laptop").performClick()
        compose.waitForIdle()
        shot("activity-expanded-light")
    }
    @Test fun activityEmpty() { show(Sample.state(tab = Tab.Activity, history = false)); shot("activity-empty-light") }

    @Test fun devicesLight() { show(Sample.state(tab = Tab.Devices)); shot("devices-light") }
    @Test fun devicesDark() { show(Sample.state(tab = Tab.Devices), ThemeMode.Dark); shot("devices-dark") }

    @Test fun settingsLight() { show(Sample.state(settings = true)); shot("settings-light") }
    @Test fun settingsDark() { show(Sample.state(settings = true), ThemeMode.Dark); shot("settings-dark") }

    @Test fun pairCode() { show(Sample.state().apply { prompt = PromptUi("LOQ Laptop", "482913") }); screen("dialog-pair-code-light") }
    @Test fun offer() {
        show(Sample.state().apply { offers.add(OfferUi("o1", "LOQ Laptop", listOf("Quarterly report.pdf", "Budget 2026.xlsx", "Photos/a.jpg", "Photos/b.jpg"), 18_400_000)) })
        screen("dialog-offer-light")
    }
    @Test fun pairOptions() { show(Sample.state(tab = Tab.Devices).apply { pairSheet = true }); screen("sheet-pair-options-light") }
    @Test fun pairOptionsDark() { show(Sample.state(tab = Tab.Devices).apply { pairSheet = true }, ThemeMode.Dark); screen("sheet-pair-options-dark") }

    @Test fun paletteOcean() { show(Sample.state(selected = true, sending = true), palette = Palette.Ocean); shot("palette-ocean-light") }
    @Test fun paletteForest() { show(Sample.state(selected = true, sending = true), palette = Palette.Forest); shot("palette-forest-light") }
    @Test fun paletteBerry() { show(Sample.state(selected = true, sending = true), ThemeMode.Dark, Palette.Berry); shot("palette-berry-dark") }
    @Test fun paletteMidnight() { show(Sample.state(selected = true, sending = true), palette = Palette.Midnight); shot("palette-midnight-light") }
    @Test fun paletteOceanDark() { show(Sample.state(tab = Tab.Devices), ThemeMode.Dark, Palette.Ocean); shot("palette-ocean-devices-dark") }
}

private object Sample {
    private const val MIN = 60_000L
    private const val DAY = 24 * 60 * MIN

    fun state(
        tab: Tab = Tab.Send, selected: Boolean = false, sending: Boolean = false, devices: Boolean = true,
        history: Boolean = true, settings: Boolean = false,
    ): HopUiState {
        val now = System.currentTimeMillis()
        val s = HopUiState()
        s.tab = tab
        s.settingsOpen = settings
        s.phone = PhoneUi("Realme 6 Pro", true, listOf(AddressUi("Wi-Fi", "192.168.8.24"), AddressUi("Hotspot", "10.82.199.70")),
            "59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419", "1.0.0")
        val moving = LiveUi("t1", false, "LOQ Laptop", LiveState.Moving("IMG_2041.jpg", 37, 128, 152_000_000, 412_000_000, 31_400_000.0, 9))
        if (devices) {
            s.devices = listOf(
                DeviceUi("a".repeat(64), "LOQ Laptop", Platform.Windows, true, now, now - 3 * DAY, true, "192.168.8.92", if (sending) moving else null),
                DeviceUi("b".repeat(64), "Galaxy Tab S9", Platform.Android, false, now - 125 * MIN, now - 12 * DAY, false, "192.168.8.40", null),
            )
            s.nearby = listOf(NearbyUi("c".repeat(64), "Pixel 8", Platform.Android, "192.168.8.31", 7410))
        }
        if (selected) {
            s.folders.add(PickedFolder(Uri.parse("content://sample/tree/photos"), "Photos", 128, 412_000_000, false))
            s.files.add(PickedFile(Uri.parse("content://sample/1"), "Holiday plan.pdf", 2_400_000, "application/pdf"))
            s.files.add(PickedFile(Uri.parse("content://sample/2"), "Interview recording.m4a", 18_900_000, "audio/mp4"))
        }
        if (sending) s.live = listOf(moving)
        if (history) {
            s.history = listOf(
                HistoryUi(now - 12 * MIN, true, "LOQ Laptop", listOf(HistoryFile("Quarterly report.pdf", null), HistoryFile("Budget 2026.xlsx", null),
                    HistoryFile("Meeting notes.txt", null)), 3, 5_300_000, 1_800, "Download/HopDrop", null, false, 3),
                HistoryUi(now - 95 * MIN, false, "LOQ Laptop", (1..6).map { HistoryFile("Photos/2026/IMG_20$it.jpg", null) }, 128, 412_000_000, 14_000,
                    "Downloads\\HopDrop", null, false, 128),
                HistoryUi(now - DAY - 30 * MIN, true, "Galaxy Tab S9", listOf(HistoryFile("Boarding pass.pdf", null)), 1, 240_000, 600,
                    "Download/HopDrop", null, false, 1),
                HistoryUi(now - DAY - 200 * MIN, false, "Galaxy Tab S9", listOf(HistoryFile("Movie night.mp4", null)), 0, 820_000_000, 0,
                    null, "You cancelled it.", true, 1),
                HistoryUi(now - 4 * DAY, false, "LOQ Laptop", listOf(HistoryFile("Thesis final.docx", null)), 0, 0, 0,
                    null, "LOQ Laptop isn't answering. Check that HopDrop is open on it and both are on the same Wi-Fi.", false, 1),
            )
        }
        return s
    }
}

private object NoActions : HopActions {
    override fun pickFiles() {}
    override fun pickPhotos() {}
    override fun pickFolder() {}
    override fun removeFile(file: PickedFile) {}
    override fun removeFolder(folder: PickedFolder) {}
    override fun clearSelection() {}
    override fun send(device: DeviceUi) {}
    override fun cancel(incoming: Boolean) {}
    override fun bluetooth() {}
    override fun scanQr() {}
    override fun showMyQr() {}
    override fun closeMyQr() {}
    override fun pairByAddress(text: String): String? = null
    override fun pairNearby(device: NearbyUi) {}
    override fun refreshNearby() {}
    override fun rename(device: DeviceUi, name: String): String? = null
    override fun remove(device: DeviceUi) {}
    override fun setTrusted(device: DeviceUi, trusted: Boolean) {}
    override fun answerPrompt(match: Boolean) {}
    override fun answerOffer(offer: OfferUi, accept: Boolean) {}
    override fun dismissPairing() {}
    override fun open(file: HistoryFile) {}
    override fun share(files: List<HistoryFile>) {}
    override fun removeFromHistory(entry: HistoryUi) {}
    override fun clearHistory() {}
    override fun setTheme(mode: ThemeMode) {}
    override fun setPalette(palette: Palette) {}
    override fun setBackground(on: Boolean) {}
    override fun setAsk(on: Boolean) {}
    override fun setPairedOnly(on: Boolean) {}
    override fun chooseReceiveFolder() {}
    override fun resetReceiveFolder() {}
    override fun setPhoneName(name: String): String? = null
    override fun allowBackground() {}
    override fun openAppSettings() {}
    override fun copy(label: String, text: String) {}
    override fun openLink(url: String) {}
}

