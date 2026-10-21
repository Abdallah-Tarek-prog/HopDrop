package com.hop.drop.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws HopDrop's screens with sample data (Preview.kt), in light and dark and in each colour theme, into
 * Source/Android/screenshots/ (./gradlew recordRoborazziDebug). Used to review the design without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class ScreenshotTests {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: HopUiState, mode: ThemeMode = ThemeMode.Light, palette: Palette = Palette.Ember) {
        compose.setContent { HopDropTheme(mode, palette) { HopDropApp(state, Preview.actions) } }
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")
    private fun screen(name: String) = captureScreenRoboImage("screenshots/$name.png")
    private fun state(
        tab: Tab = Tab.Send, selected: Boolean = false, sending: Boolean = false, devices: Boolean = true,
        history: Boolean = true, settings: Boolean = false,
    ) = Preview.state(tab, selected, sending, devices, history, settings, text = true)

    /** Each page of the tour; [next] taps Next that many times first. */
    private fun tour(page: Int, mode: ThemeMode = ThemeMode.Light, devices: Boolean = false, name: String? = null) {
        show(state(devices = devices).apply { onboarding = true }, mode)
        repeat(page) { compose.onNodeWithText("Next").performClick(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(700)
        shot(name ?: "tour-${page + 1}-${if (mode == ThemeMode.Dark) "dark" else "light"}")
    }

    @Test fun tour1() = tour(0)
    @Test fun tour2() = tour(1)
    @Test fun tour3() = tour(2)
    @Test fun tour4() = tour(3)
    @Test fun tour5() = tour(4)
    @Test fun tour6() = tour(5)
    @Test fun tour7() = tour(6)
    @Test fun tour3Dark() = tour(2, ThemeMode.Dark)
    @Test fun tour5Dark() = tour(4, ThemeMode.Dark)
    @Test fun tour6Dark() = tour(5, ThemeMode.Dark)
    @Test fun tour7Returning() = tour(6, devices = true, name = "tour-7-returning-light")
    @Test @Config(qualifiers = "w320dp-h568dp-xxhdpi") fun tour4Small() = tour(3, name = "tour-4-small-light")
    @Test @Config(qualifiers = "w411dp-h852dp-xxhdpi") fun tour4Phone() = tour(3, name = "tour-4-411-light")
    @Test @Config(qualifiers = "w411dp-h852dp-xxhdpi") fun tour4LargeText() {
        RuntimeEnvironment.setFontScale(1.5f)
        tour(3, name = "tour-4-411-large-text-light")
    }
    @Test @Config(qualifiers = "w320dp-h568dp-xxhdpi") fun tour4SmallLargeText() {
        RuntimeEnvironment.setFontScale(1.5f)
        tour(3, name = "tour-4-small-large-text-light")
    }
    @Test fun tour6LargeText() {
        RuntimeEnvironment.setFontScale(1.5f)
        tour(5, name = "tour-6-large-text-light")
    }
    @Test fun whatsNew() {
        show(state().apply { whatsNewVersion = 3 })
        compose.mainClock.advanceTimeBy(250)
        screen("modal-whats-new-light")
    }
    @Test fun whatsNewDark() {
        show(state().apply { whatsNewVersion = 3 }, ThemeMode.Dark)
        compose.mainClock.advanceTimeBy(250)
        screen("modal-whats-new-dark")
    }

    private fun quickSendCard(mode: ThemeMode, width: Int, largeText: Boolean = false) {
        if (largeText) RuntimeEnvironment.setFontScale(1.5f)
        val devices = listOf(QuickSendDevice("pc", "LOQ Laptop", Platform.Windows),
            QuickSendDevice("tablet", "Galaxy Tab S9", Platform.Android))
        compose.setContent {
            HopDropTheme(mode, Palette.Ember) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.BottomCenter) {
                    QuickSendCard(
                        devices = devices, selected = "pc", clipboardDetail = "A note copied from the browser", clipboardAvailable = true,
                        photoDetail = "Allow photos", photoAvailable = true, photoPreview = null,
                        onSelect = {}, onClipboard = {}, onPhoto = {}, onFiles = {},
                        modifier = Modifier.padding(16.dp).widthIn(max = 400.dp).fillMaxWidth(),
                    )
                }
            }
        }
        compose.waitForIdle()
        shot("quick-send-card-$width-${if (mode == ThemeMode.Dark) "dark" else "light"}${if (largeText) "-large-text" else ""}")
    }

    @Test @Config(qualifiers = "w411dp-h852dp-xxhdpi") fun quickSend411Light() = quickSendCard(ThemeMode.Light, 411)
    @Test @Config(qualifiers = "w411dp-h852dp-xxhdpi") fun quickSend411Dark() = quickSendCard(ThemeMode.Dark, 411)
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun quickSend360Light() = quickSendCard(ThemeMode.Light, 360)
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun quickSend360Dark() = quickSendCard(ThemeMode.Dark, 360)
    @Test @Config(qualifiers = "w320dp-h568dp-xxhdpi") fun quickSend320Light() = quickSendCard(ThemeMode.Light, 320)
    @Test @Config(qualifiers = "w320dp-h568dp-xxhdpi") fun quickSend320Dark() = quickSendCard(ThemeMode.Dark, 320)
    @Test @Config(qualifiers = "w411dp-h852dp-xxhdpi") fun quickSend411LargeTextLight() = quickSendCard(ThemeMode.Light, 411, true)
    @Test @Config(qualifiers = "w411dp-h852dp-xxhdpi") fun quickSend411LargeTextDark() = quickSendCard(ThemeMode.Dark, 411, true)
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun quickSend360LargeTextLight() = quickSendCard(ThemeMode.Light, 360, true)
    @Test @Config(qualifiers = "w360dp-h800dp-xxhdpi") fun quickSend360LargeTextDark() = quickSendCard(ThemeMode.Dark, 360, true)
    @Test @Config(qualifiers = "w320dp-h568dp-xxhdpi") fun quickSend320LargeTextLight() = quickSendCard(ThemeMode.Light, 320, true)
    @Test @Config(qualifiers = "w320dp-h568dp-xxhdpi") fun quickSend320LargeTextDark() = quickSendCard(ThemeMode.Dark, 320, true)

    @Test fun sendEmptyLight() { show(state()); shot("send-empty-light") }
    @Test fun sendEmptyDark() { show(state(), ThemeMode.Dark); shot("send-empty-dark") }
    @Test fun sendSelectedLight() { show(state(selected = true, sending = true)); shot("send-selected-light") }
    @Test fun sendSelectedDark() { show(state(selected = true, sending = true), ThemeMode.Dark); shot("send-selected-dark") }
    @Test fun sendFirstRun() { show(state(devices = false)); shot("send-first-run-light") }
    @Test fun sendPicking() { show(state(selected = true).apply { picking = setOf("a".repeat(64)) }); shot("send-several-light") }
    @Test fun sendWaiting() {
        show(state(selected = true).apply { devices = devices.mapIndexed { i, d -> if (i == 1) d.copy(waiting = 2) else d } })
        shot("send-waiting-light")
    }
    @Test fun sendDirect() {
        show(state().apply { directTo = "a".repeat(64); devices = devices.map { if (it.hasLink) it.copy(direct = true) else it } }, ThemeMode.Dark)
        shot("send-direct-dark")
    }
    @Test fun textDialog() {
        show(state())
        // The text field's cursor blinks forever, so the clock is moved by hand instead of waiting for the screen to settle.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Text").performClick()
        compose.mainClock.advanceTimeBy(600)
        screen("dialog-text-light")
    }

    @Test fun activityLight() { show(state(tab = Tab.Activity)); shot("activity-light") }
    @Test fun activityDark() { show(state(tab = Tab.Activity), ThemeMode.Dark); shot("activity-dark") }
    @Test fun activityExpanded() {
        show(state(tab = Tab.Activity))
        compose.onNodeWithText("From LOQ Laptop").performClick()
        compose.waitForIdle()
        shot("activity-expanded-light")
    }
    @Test fun activityEmpty() { show(state(tab = Tab.Activity, history = false)); shot("activity-empty-light") }

    @Test fun devicesLight() { show(state(tab = Tab.Devices)); shot("devices-light") }
    @Test fun devicesDark() { show(state(tab = Tab.Devices), ThemeMode.Dark); shot("devices-dark") }

    @Test fun settingsLight() { show(state(settings = true)); shot("settings-light") }
    @Test fun settingsDark() { show(state(settings = true), ThemeMode.Dark); shot("settings-dark") }
    @Test fun settingsBackup() {
        show(state(settings = true).apply {
            settings = settings.copy(backups = listOf(BackupUi(id = "sample", on = true, folder = "Camera", device = "LOQ Laptop",
                status = "Up to date", lastRun = System.currentTimeMillis() - 3_600_000)))
        })
        shot("settings-backup-light")
    }
    private val sampleBackups = listOf(
        BackupUi(id = "camera", on = true, folder = "Camera", device = "LOQ Laptop", deviceId = "a".repeat(64), kinds = 1 or 2,
            charging = true, timed = true, from = 60, to = 360, status = "Up to date", lastRun = System.currentTimeMillis() - 3_600_000),
        BackupUi(id = "docs", on = false, folder = "Documents", device = "Galaxy Tab S9", deviceId = "b".repeat(64), status = null),
    )
    @Test fun backupsList() {
        show(state(settings = true).apply { settings = settings.copy(backups = sampleBackups); backupsOpen = true })
        shot("backups-list-light")
    }
    @Test fun backupEditor() {
        show(state(settings = true).apply { settings = settings.copy(backups = sampleBackups); backupsOpen = true; backupOpen = "camera" })
        shot("backup-editor-light")
    }
    @Test fun backupsReceived() {
        show(state(settings = true).apply {
            settings = settings.copy(receivedBackups = listOf(ReceivedBackupUi("c".repeat(64), "Pixel 8", "Camera", 1240,
                System.currentTimeMillis() - 7_200_000, "default", "Download/HopDrop/Backups")))
            receivedBackupsOpen = true
        })
        shot("backups-received-light")
    }
    @Test fun backupSetup() { show(state(settings = true).apply { backupSetup = BackupSetupUi("Camera", 1240, 0) }); screen("dialog-backup-light") }
    @Test fun updateOffer() { show(state().apply { update = UpdateUi("1.1.1", 1_900_000) }); shot("send-update-light") }
    @Test fun updateDownloading() { show(state().apply { update = UpdateUi("1.1.1", 1_900_000, downloading = true, fraction = 0.4f) }, ThemeMode.Dark); shot("send-update-dark") }

    @Test fun pairCode() { show(state().apply { prompt = PromptUi("LOQ Laptop", "482913") }); screen("dialog-pair-code-light") }
    @Test fun pairCodeDark() { show(state().apply { prompt = PromptUi("LOQ Laptop", "482913") }, ThemeMode.Dark); screen("modal-pair-code-dark") }
    @Test fun pairingDirect() { show(state().apply { pairing = PairingUi("LOQ Laptop", direct = true) }); screen("dialog-pairing-direct-light") }
    @Test fun offer() {
        show(state().apply { offers.add(OfferUi("o1", "LOQ Laptop", listOf("Quarterly report.pdf", "Budget 2026.xlsx", "Photos/a.jpg", "Photos/b.jpg"), 18_400_000)) })
        screen("dialog-offer-light")
    }
    @Test fun qrModal() { show(Preview.state(tab = Tab.Devices, qr = true)); screen("modal-qr-light") }
    @Test fun readyModal() { show(state()); compose.onNodeWithText("Ready").performClick(); compose.waitForIdle(); screen("modal-ready-light") }
    @Test fun deviceDetails() { show(state(tab = Tab.Devices).apply { deviceOpen = devices.first().id }); shot("device-details-light") }
    @Test fun deviceDetailsDark() { show(state(tab = Tab.Devices).apply { deviceOpen = devices.first().id }, ThemeMode.Dark); shot("device-details-dark") }
    @Test fun allFiles() { show(state(tab = Tab.Activity).apply { allFilesOf = history.first { it.count == 128 } }); shot("all-files-light") }
    @Test fun activityMenu() {
        show(state(tab = Tab.Activity))
        compose.onNodeWithText("From LOQ Laptop").performTouchInput { longClick() }
        compose.waitForIdle()
        screen("menu-activity-light")
    }
    @Test fun removeDeviceModal() {
        show(state(tab = Tab.Devices).apply { deviceOpen = devices.first().id })
        compose.onNodeWithText("Remove device").performClick()
        compose.waitForIdle()
        screen("modal-remove-device-light")
    }

    @Test fun paletteOcean() { show(state(selected = true, sending = true), palette = Palette.Ocean); shot("palette-ocean-light") }
    @Test fun paletteForest() { show(state(selected = true, sending = true), palette = Palette.Forest); shot("palette-forest-light") }
    @Test fun paletteBerry() { show(state(selected = true, sending = true), ThemeMode.Dark, Palette.Berry); shot("palette-berry-dark") }
    @Test fun paletteMidnight() { show(state(selected = true, sending = true), palette = Palette.Midnight); shot("palette-midnight-light") }
    @Test fun paletteOceanDark() { show(state(tab = Tab.Devices), ThemeMode.Dark, Palette.Ocean); shot("palette-ocean-devices-dark") }
}
