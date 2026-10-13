package com.hop.drop.ui

import android.net.Uri
import java.lang.reflect.Proxy

/**
 * Sample data and do-nothing actions, for drawing HopDrop's real screens where nothing should happen: the tour's
 * phone pictures (Onboarding.kt) and the screenshot tests.
 */
object Preview {
    private const val MIN = 60_000L
    private const val DAY = 24 * 60 * MIN

    /** Every action does nothing (and every question gets "no answer"). */
    val actions: HopActions = Proxy.newProxyInstance(HopActions::class.java.classLoader, arrayOf(HopActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Preview actions"
            else -> null
        }
    } as HopActions

    fun state(
        tab: Tab = Tab.Send, selected: Boolean = false, sending: Boolean = false, devices: Boolean = true,
        history: Boolean = true, settings: Boolean = false, text: Boolean = false,
    ): HopUiState {
        val now = System.currentTimeMillis()
        val s = HopUiState()
        s.tab = tab
        s.settingsOpen = settings
        s.phone = PhoneUi("Realme 6 Pro", true, listOf(AddressUi("Wi-Fi", "192.168.8.24"), AddressUi("Hotspot", "10.82.199.70")),
            "59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419", "1.1.0")
        val moving = LiveUi("t1", false, "LOQ Laptop", LiveState.Moving("IMG_2041.jpg", 37, 128, 152_000_000, 412_000_000, 31_400_000.0, 9))
        if (devices) {
            s.devices = listOf(
                DeviceUi("a".repeat(64), "LOQ Laptop", Platform.Windows, true, now, now - 3 * DAY, true, "192.168.8.92", if (sending) moving else null,
                    hasLink = true),
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
            s.history = buildList {
                if (text) add(HistoryUi(now - 3 * MIN, true, "LOQ Laptop", emptyList(), 0, 46, 0, null, null, false, 0,
                    text = "https://github.com/Abdallah-Tarek-prog/HopDrop"))
                add(HistoryUi(now - 12 * MIN, true, "LOQ Laptop", listOf(HistoryFile("Quarterly report.pdf", null), HistoryFile("Budget 2026.xlsx", null),
                    HistoryFile("Meeting notes.txt", null)), 3, 5_300_000, 1_800, "Download/HopDrop", null, false, 3))
                add(HistoryUi(now - 95 * MIN, false, "LOQ Laptop", (1..6).map { HistoryFile("Photos/2026/IMG_20$it.jpg", null) }, 128, 412_000_000, 14_000,
                    "Downloads\\HopDrop", null, false, 128))
                add(HistoryUi(now - DAY - 30 * MIN, true, "Galaxy Tab S9", listOf(HistoryFile("Boarding pass.pdf", null)), 1, 240_000, 600,
                    "Download/HopDrop", null, false, 1))
                add(HistoryUi(now - DAY - 200 * MIN, false, "Galaxy Tab S9", listOf(HistoryFile("Movie night.mp4", null)), 0, 820_000_000, 0,
                    null, "You cancelled it.", true, 1))
                add(HistoryUi(now - 4 * DAY, false, "LOQ Laptop", listOf(HistoryFile("Thesis final.docx", null)), 0, 0, 0,
                    null, "LOQ Laptop isn't answering. Check that HopDrop is open on it and both are on the same Wi-Fi.", false, 1))
            }
        }
        return s
    }
}
