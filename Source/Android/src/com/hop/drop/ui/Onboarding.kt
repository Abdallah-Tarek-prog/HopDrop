package com.hop.drop.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.SignalCellular4Bar
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.launch

/**
 * The first-start tour (also Settings → Show the tour again): seven short pages with pictures and bullets.
 * Swipe or tap Next; Skip ends it from any page. The last page leads
 * straight to pairing, so a new user goes from opening HopDrop to a paired PC in the fewest taps.
 */
@Composable
fun Onboarding(state: HopUiState, actions: HopActions) {
    val pages = remember(state.devices.isEmpty()) { tourPages(firstTime = state.devices.isEmpty()) }
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Top: logo and Skip.
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(26.dp)
                Spacer(Modifier.width(8.dp))
                Text("HopDrop", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (!last) TextButton(onClick = { actions.finishOnboarding(false) }) { Text("Skip") }
            }
            HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                TourPage(pages[index], index == pager.currentPage)
            }
            // Bottom: page dots, then Next (or, on the last page, what to do now).
            Dots(pages.size, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp))
            Box(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp).heightIn(min = 112.dp)) {
                AnimatedContent(last, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "tour-buttons") { end ->
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (end) {
                            Button(onClick = { actions.finishOnboarding(state.devices.isEmpty()) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text(if (state.devices.isEmpty()) "Pair my first device" else "Start sending", style = MaterialTheme.typography.titleMedium)
                            }
                            if (state.devices.isEmpty()) TextButton(onClick = { actions.finishOnboarding(false) }, modifier = Modifier.padding(top = 4.dp)) {
                                Text("I'll do it later")
                            }
                        } else {
                            Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                                Text("Next", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One page of the tour: a picture, a title and short action bullets. */
private class TourPage(
    val title: String,
    val bullets: List<TourBullet>,
    val picture: @Composable (active: Boolean, height: Dp) -> Unit,
)

private class TourBullet(val icon: ImageVector, val lead: String, val rest: String)

private val whatsNew = mapOf(2 to listOf(
    TourBullet(Icons.Rounded.WifiTethering, "Connect directly", " without shared Wi-Fi."),
    TourBullet(Icons.Rounded.ContentPaste, "Send text and clipboard", " both ways."),
    TourBullet(Icons.Rounded.CheckCircle, "Sends wait", " until an away device returns."),
    TourBullet(Icons.Rounded.TouchApp, "Share faster", " from any app or the Quick Settings tile."),
))

internal fun hasWhatsNew(version: Int): Boolean = whatsNew.containsKey(version)

@Composable
internal fun WhatsNew(state: HopUiState, actions: HopActions) {
    val version = state.whatsNewVersion ?: return
    val title = "What's new in " + state.phone.version.split('.').take(2).joinToString(".")
    HopModal(
        onDismissRequest = { actions.dismissWhatsNew(false) }, paneTitle = title,
        title = { Text(title) },
        text = { BulletList(whatsNew[version].orEmpty()) },
        confirmButton = { Button(onClick = { actions.dismissWhatsNew(false) }) { Text("Got it") } },
        dismissButton = { TextButton(onClick = { actions.dismissWhatsNew(true) }) { Text("Show the tour") } },
    )
}

private fun tourPages(firstTime: Boolean): List<TourPage> = listOf(
    TourPage(
        "Phone to PC in a hop",
        listOf(TourBullet(Icons.Rounded.Laptop, "Send", " photos and files between your devices."),
            TourBullet(Icons.Rounded.Lock, "Keep it private.", " No cloud or account.")),
    ) { active, height -> HopIllustration(active, height) },
    TourPage(
        "Pair once with a scan",
        listOf(TourBullet(Icons.Rounded.QrCode2, "Scan", " the code on your PC once."),
            TourBullet(Icons.Rounded.WifiTethering, "Any network works.", " Your phone can connect directly.")),
    ) { _, height -> PairIllustration(height) },
    TourPage(
        "Send in two taps",
        listOf(TourBullet(Icons.Rounded.Folder, "Pick", " what to send."),
            TourBullet(Icons.AutoMirrored.Rounded.ArrowForward, "Tap Send", " beside your device."),
            TourBullet(Icons.Rounded.Laptop, "Select several", " to send to more devices.")),
    ) { active, height ->
        PhoneMock(height, Preview.state(selected = true), spotlight = Spot(0.044f, 0.678f, 0.912f, 0.106f, "Tap Send next to a device"), active = active)
    },
    TourPage(
        "Share from any app",
        listOf(TourBullet(Icons.Rounded.Image, "In your gallery, tap Share.", ""),
            TourBullet(Icons.Rounded.Laptop, "Tap your PC", ": the round icon with the small HopDrop badge."),
            TourBullet(Icons.Rounded.CheckCircle, "It's sent.", " HopDrop doesn't even open.")),
    ) { active, height -> ShareSheetIllustration(active, height) },
    TourPage(
        "The Quick Settings tile",
        listOf(TourBullet(Icons.Rounded.TouchApp, "Pull down", " quick settings and tap HopDrop."),
            TourBullet(Icons.Rounded.ContentPaste, "Send", " your clipboard or latest photo. Pick files too."),
            TourBullet(Icons.Rounded.CheckCircle, "Add the tile:", if (Build.VERSION.SDK_INT >= 33) " Settings → Add the quick send tile." else " Edit quick settings and drag HopDrop in.")),
    ) { active, height -> QuickTileIllustration(active, height) },
    TourPage(
        "Everything lands in Activity",
        listOf(TourBullet(Icons.Rounded.Folder, "Files", " save in Download/HopDrop, even when HopDrop is closed."),
            TourBullet(Icons.Rounded.ContentPaste, "Links and text", " are ready to copy."),
            TourBullet(Icons.Rounded.CheckCircle, "Away device?", " Your send waits until it's back.")),
    ) { active, height ->
        PhoneMock(height, Preview.state(tab = Tab.Activity, text = true), spotlight = Spot(0.044f, 0.348f, 0.912f, 0.213f, "Copy or open it in one tap"), active = active)
    },
    TourPage(
        if (firstTime) "Ready when you are" else "You're all set",
        if (firstTime) listOf(TourBullet(Icons.Rounded.Laptop, "Install HopDrop", " on your PC from Microsoft Store or github.com/Abdallah-Tarek-prog/HopDrop."),
            TourBullet(Icons.Rounded.QrCode2, "Scan", " the code shown on your PC."))
        else listOf(TourBullet(Icons.Rounded.CheckCircle, "Your devices are paired.", ""),
            TourBullet(Icons.AutoMirrored.Rounded.ArrowForward, "Start sending", " whenever you're ready.")),
    ) { active, height -> ReadyIllustration(active, height) },
)

/**
 * One page: the title and bullets are measured first and the picture gets the room they leave (at most 370 dp, at least
 * 120 dp), so large text shrinks the picture instead of being cut off. Only when even that isn't enough does the page scroll.
 */
@Composable
private fun TourPage(page: TourPage, active: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val room = maxHeight
        SubcomposeLayout(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { constraints ->
            val width = constraints.maxWidth
            val words = subcompose("words") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(14.dp))
                    Text(page.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    BulletList(page.bullets)
                    Spacer(Modifier.height(8.dp))
                }
            }.first().measure(Constraints(minWidth = width, maxWidth = width))
            val least = 120.dp.roundToPx()
            val most = maxOf(least, min(370.dp, room * 0.56f).roundToPx())
            val pictureHeight = (room.roundToPx() - words.height).coerceIn(least, most)
            val picture = subcompose("picture") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) { page.picture(active, pictureHeight.toDp()) }
                }
            }.first().measure(Constraints.fixed(width, pictureHeight))
            layout(width, pictureHeight + words.height) {
                picture.place(0, 0)
                words.place(0, pictureHeight)
            }
        }
    }
}

@Composable
private fun BulletList(bullets: List<TourBullet>) {
    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (bullet in bullets) Row(verticalAlignment = Alignment.Top) {
            Icon(bullet.icon, contentDescription = null, tint = primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = primary, fontWeight = FontWeight.SemiBold)) { append(bullet.lead) }
                append(bullet.rest)
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Dots(count: Int, current: Int, modifier: Modifier) {
    Row(modifier.semantics { contentDescription = "Page ${current + 1} of $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, tween(220), label = "dot")
            val color by animateColorAsState(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dot-colour")
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

// ---------------- Pictures ----------------

/** A highlighted part of a phone picture, as fractions of the screen, with a short label beside it. */
private class Spot(val x: Float, val y: Float, val w: Float, val h: Float, val label: String)

private val MockWidth = 360.dp
private val MockHeight = 760.dp

/**
 * The real HopDrop screen ([state] drawn by the app's own composables) inside a phone outline, scaled to [height]. It
 * can't be touched. [spotlight] dims everything but one part and pulses a ring around it.
 */
@Composable
private fun PhoneMock(height: Dp, state: HopUiState, spotlight: Spot?, active: Boolean) {
    val bezel = 7.dp
    val scale = (height - bezel * 2) / MockHeight
    val width = MockWidth * scale + bezel * 2
    val pulse = rememberInfiniteTransition(label = "spot")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring")
    Box(
        Modifier.size(width, height).shadow(20.dp, RoundedCornerShape(36.dp)).clip(RoundedCornerShape(36.dp))
            .background(Color(0xFF16171B)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(36.dp)).padding(bezel)
            .clearAndSetSemantics { contentDescription = "Picture of HopDrop" },
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.wrapContentSize(unbounded = true, align = Alignment.TopStart)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
                .requiredSize(MockWidth, MockHeight)) {
                // Fixed text size inside the picture, so the highlighted parts are where the tour says they are.
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                    Column(Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.safeDrawing)) {
                        MockStatusBar()
                        Box(Modifier.weight(1f)) { MainScreen(state, Preview.actions, remember { SnackbarHostState() }) }
                    }
                }
                if (spotlight != null && active) {
                    val dim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)
                    val ringColor = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) {
                        val left = spotlight.x * size.width; val top = spotlight.y * size.height
                        val w = spotlight.w * size.width; val h = spotlight.h * size.height
                        val hole = Path().apply {
                            addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + w, top + h, CornerRadius(18.dp.toPx())))
                        }
                        val all = Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
                        drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, all, hole), dim)
                        val grow = ring * 10.dp.toPx()
                        drawRoundRect(ringColor.copy(alpha = 1f - ring), Offset(left - grow, top - grow), Size(w + grow * 2, h + grow * 2),
                            CornerRadius(18.dp.toPx() + grow), style = Stroke(3.dp.toPx()))
                        drawRoundRect(ringColor, Offset(left, top), Size(w, h), CornerRadius(18.dp.toPx()), style = Stroke(2.5.dp.toPx()))
                    }
                    // The label hangs on the ring's bottom edge, centred, like a tag.
                    Box(Modifier.offset(x = MockWidth * spotlight.x, y = MockHeight * (spotlight.y + spotlight.h) - 15.dp).width(MockWidth * spotlight.w),
                        contentAlignment = Alignment.Center) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(14.dp), shadowElevation = 4.dp) {
                            Text(spotlight.label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                        }
                    }
                }
                // Pictures can't be used: they only show what the real screen looks like.
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { if (it.pressed) it.consume() } }
                })
            }
        }
    }
}

@Composable
private fun MockStatusBar() {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("9:41", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        for (icon in listOf(Icons.Rounded.Wifi, Icons.Rounded.SignalCellular4Bar, Icons.Rounded.Battery5Bar)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(14.dp))
        }
    }
}

/** A phone and a laptop with a dot hopping between them along HopDrop's arc. */
@Composable
private fun HopIllustration(active: Boolean, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    val hop = rememberInfiniteTransition(label = "hop")
    val t by hop.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val side = min(height, 340.dp)
    Box(Modifier.size(side), contentAlignment = Alignment.Center) {
        Box(Modifier.size(side * 0.92f).clip(CircleShape).background(Brush.radialGradient(listOf(scheme.primaryContainer, scheme.surface))))
        Canvas(Modifier.fillMaxSize()) {
            // From the top of the phone to the top of the laptop, so the hopping dot is never hidden behind them.
            val start = Offset(size.width * 0.24f, size.height * 0.555f)
            val end = Offset(size.width * 0.79f, size.height * 0.495f)
            val path = Path().apply { moveTo(start.x, start.y); quadraticTo(size.width * 0.5f, size.height * 0.08f, end.x, end.y) }
            drawPath(path, Brush.linearGradient(listOf(Color(0xFFFFB02A), Color(0xFFF92B3C)), start, end),
                style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 18.dp.toPx()))))
            if (active) {
                val measure = PathMeasure().apply { setPath(path, false) }
                val eased = FastOutSlowInEasing.transform(t)
                val dot = measure.getPosition(measure.length * eased)
                drawCircle(Color(0xFFFF8A30).copy(alpha = 0.25f), 18.dp.toPx(), dot)
                drawCircle(Brush.linearGradient(listOf(Color(0xFFFF8A30), Color(0xFFF7224B)), dot - Offset(10f, 10f), dot + Offset(10f, 10f)), 10.dp.toPx(), dot)
            }
        }
        DeviceBadge(Icons.Rounded.PhoneAndroid, side * 0.22f, Modifier.align(Alignment.BottomStart).offset(x = side * 0.13f, y = -side * 0.18f))
        DeviceBadge(Icons.Rounded.Laptop, side * 0.26f, Modifier.align(Alignment.BottomEnd).offset(x = -side * 0.08f, y = -side * 0.22f))
    }
}

@Composable
private fun DeviceBadge(icon: ImageVector, size: Dp, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(size * 0.28f), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 6.dp, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.56f)) }
    }
}

/** A laptop showing HopDrop's pairing code, and a phone scanning it; a badge says it works across networks. */
@Composable
private fun PairIllustration(height: Dp) {
    val scheme = MaterialTheme.colorScheme
    val w = min(height * 0.95f, 360.dp)
    Box(Modifier.size(w, height * 0.9f)) {
        // Laptop: screen with the code, then its base.
        Column(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(w * 0.86f, w * 0.54f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF16171B)).padding(8.dp)) {
                Row(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(scheme.surfaceContainerLow).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(w * 0.30f).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp)) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = Color.Black, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Scan with HopDrop on your phone", style = MaterialTheme.typography.labelLarge, maxLines = 3)
                        Text("Devices page", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Box(Modifier.size(w * 0.98f, 10.dp).clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)).background(Color(0xFF2A2C33)))
        }
        // Phone in front, scanning.
        Box(Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp).size(w * 0.34f, w * 0.62f).shadow(16.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp)).background(Color(0xFF16171B)).padding(5.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(Color(0xFF2B2D33)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(w * 0.2f).border(3.dp, scheme.primary, RoundedCornerShape(10.dp)).padding(6.dp)) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxSize())
                }
            }
        }
        // "No shared Wi-Fi needed".
        Surface(shape = CircleShape, color = scheme.secondaryContainer, shadowElevation = 2.dp, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 18.dp)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Any network", style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
            }
        }
    }
}

/** Keeps drawn pictures readable on narrow phones without squeezing their rows. */
@Composable
private fun ScaledPicture(height: Dp, baseHeight: Dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        val scale = minOf(maxWidth / 340.dp, height / baseHeight, 1f)
        Box(Modifier.size(340.dp * scale, baseHeight * scale)) {
            Box(Modifier.wrapContentSize(unbounded = true, align = Alignment.TopStart).graphicsLayer {
                scaleX = scale; scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
            }.requiredSize(340.dp, baseHeight)) { content() }
        }
    }
}

/** Android's share sheet with the paired PC in its top row, highlighted. */
@Composable
private fun ShareSheetIllustration(active: Boolean, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "share")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring")
    ScaledPicture(height, 292.dp) {
        Column(Modifier.width(340.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerHigh, shadowElevation = 10.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp).clip(CircleShape).background(scheme.outlineVariant))
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Color(0xFF6FA8DC), Color(0xFF3D6FA8)))))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Sharing 3 photos", style = MaterialTheme.typography.titleSmall)
                            Text("Gallery", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ShareTarget("LOQ Laptop", Icons.Rounded.Laptop, highlight = true, ring = if (active) ring else -1f)
                        ShareTarget("Galaxy Tab", Icons.Rounded.PhoneAndroid)
                        ShareTarget("Messages", null, Color(0xFF5BB974))
                        ShareTarget("Email", null, Color(0xFFE8705F))
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for ((name, color) in listOf("Drive" to Color(0xFFF2C14E), "HopDrop" to Color(0xFF121212), "Notes" to Color(0xFF8E7CC3), "Copy" to Color(0xFF9AA0A6))) {
                            ShareTarget(name, null, color, logo = name == "HopDrop")
                        }
                    }
                }
            }
        }
    }
}

/** A drawn quick-settings panel and the floating card opened by its tile. */
@Composable
private fun QuickTileIllustration(active: Boolean, height: Dp) {
    val scheme = MaterialTheme.colorScheme
    val pulse = rememberInfiniteTransition(label = "quick tile")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring")
    ScaledPicture(height, 420.dp) {
        Box(Modifier.size(340.dp, 420.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF24262E))) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("9:41", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (name in listOf("Internet", "Bluetooth")) {
                        Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF454851), modifier = Modifier.weight(1f)) {
                            Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box {
                    Surface(shape = RoundedCornerShape(18.dp), color = scheme.primary) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LogoMark(24.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("HopDrop", color = scheme.onPrimary, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    if (active) Box(Modifier.matchParentSize().border(3.dp, scheme.onPrimary.copy(alpha = 1f - ring * 0.6f), RoundedCornerShape(18.dp)))
                }
            }
            Surface(shape = RoundedCornerShape(24.dp), color = scheme.surfaceContainerLow, shadowElevation = 10.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogoMark(22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Quick send", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Send to", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp))
                    Surface(shape = CircleShape, color = scheme.primaryContainer) {
                        Text("LOQ Laptop", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((icon, label) in listOf(Icons.Rounded.ContentPaste to "Clipboard", Icons.Rounded.Image to "Latest photo",
                            Icons.AutoMirrored.Rounded.InsertDriveFile to "Files")) {
                            Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(24.dp))
                                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                        modifier = Modifier.padding(top = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareTarget(name: String, icon: ImageVector?, color: Color = Color.Unspecified, highlight: Boolean = false, ring: Float = -1f, logo: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.width(68.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (ring >= 0f) Box(Modifier.size(52.dp + 14.dp * ring).clip(CircleShape).background(scheme.primary.copy(alpha = 0.35f * (1f - ring))))
            Box(Modifier.size(52.dp).clip(CircleShape).background(if (icon != null) scheme.primary else color)
                .then(if (highlight) Modifier.border(3.dp, scheme.primaryContainer, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                when {
                    icon != null -> Icon(icon, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(26.dp))
                    logo -> LogoMark(32.dp)
                }
            }
            if (icon != null) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Color(0xFF121212)).padding(3.dp)) { LogoMark(14.dp) }
        }
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The last page: the logo, and what HopDrop promises. */
@Composable
private fun ReadyIllustration(active: Boolean, height: Dp) {
    ScaledPicture(height, 190.dp) { ReadyPicture(active) }
}

/** Just the pulsing logo: the page's bullets say what to do next, so the picture adds no more words. */
@Composable
private fun ReadyPicture(active: Boolean) {
    val glow = rememberInfiniteTransition(label = "ready")
    val pulse by glow.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(160.dp).graphicsLayer { val s = if (active) pulse else 1f; scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(48.dp)).background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
            LogoMark(124.dp)
        }
    }
}
