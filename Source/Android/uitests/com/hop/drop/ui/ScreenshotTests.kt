package com.hop.drop.ui

import android.app.Application
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
    private fun tour(page: Int, mode: ThemeMode = ThemeMode.Light, devices: Boolean = false) {
        show(state(devices = devices).apply { onboarding = true }, mode)
        repeat(page) { compose.onNodeWithText("Next").performClick(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(700)
        shot("tour-${page + 1}-${if (mode == ThemeMode.Dark) "dark" else "light"}")
    }

    @Test fun tour1() = tour(0)
    @Test fun tour2() = tour(1)
    @Test fun tour3() = tour(2)
    @Test fun tour4() = tour(3)
    @Test fun tour5() = tour(4)
    @Test fun tour6() = tour(5)
    @Test fun tour3Dark() = tour(2, ThemeMode.Dark)
    @Test fun tour5Dark() = tour(4, ThemeMode.Dark)
    @Test fun tour6Returning() { show(state().apply { onboarding = true }); repeat(5) { compose.onNodeWithText("Next").performClick(); compose.waitForIdle() }; shot("tour-6-returning-light") }

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
            settings = settings.copy(backup = BackupUi(true, "Camera", "LOQ Laptop", false, "Up to date", System.currentTimeMillis() - 3_600_000))
        })
        shot("settings-backup-light")
    }
    @Test fun backupSetup() { show(state(settings = true).apply { backupSetup = BackupSetupUi("Camera", 1240, 0) }); screen("dialog-backup-light") }
    @Test fun updateOffer() { show(state().apply { update = UpdateUi("1.1.1", 1_900_000) }); shot("send-update-light") }
    @Test fun updateDownloading() { show(state().apply { update = UpdateUi("1.1.1", 1_900_000, downloading = true, fraction = 0.4f) }, ThemeMode.Dark); shot("send-update-dark") }

    @Test fun pairCode() { show(state().apply { prompt = PromptUi("LOQ Laptop", "482913") }); screen("dialog-pair-code-light") }
    @Test fun pairingDirect() { show(state().apply { pairing = PairingUi("LOQ Laptop", direct = true) }); screen("dialog-pairing-direct-light") }
    @Test fun offer() {
        show(state().apply { offers.add(OfferUi("o1", "LOQ Laptop", listOf("Quarterly report.pdf", "Budget 2026.xlsx", "Photos/a.jpg", "Photos/b.jpg"), 18_400_000)) })
        screen("dialog-offer-light")
    }
    @Test fun pairOptions() { show(state(tab = Tab.Devices).apply { pairSheet = true }); screen("sheet-pair-options-light") }
    @Test fun pairOptionsDark() { show(state(tab = Tab.Devices).apply { pairSheet = true }, ThemeMode.Dark); screen("sheet-pair-options-dark") }

    @Test fun paletteOcean() { show(state(selected = true, sending = true), palette = Palette.Ocean); shot("palette-ocean-light") }
    @Test fun paletteForest() { show(state(selected = true, sending = true), palette = Palette.Forest); shot("palette-forest-light") }
    @Test fun paletteBerry() { show(state(selected = true, sending = true), ThemeMode.Dark, Palette.Berry); shot("palette-berry-dark") }
    @Test fun paletteMidnight() { show(state(selected = true, sending = true), palette = Palette.Midnight); shot("palette-midnight-light") }
    @Test fun paletteOceanDark() { show(state(tab = Tab.Devices), ThemeMode.Dark, Palette.Ocean); shot("palette-ocean-devices-dark") }
}
