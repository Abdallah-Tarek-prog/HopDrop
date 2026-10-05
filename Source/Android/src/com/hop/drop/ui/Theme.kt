package com.hop.drop.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hop.drop.R

/** Light, dark, or whatever the phone uses. Stored as 0 / 1 / 2 ("theme" in settings). */
enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/**
 * The colour themes people can pick in Settings. Every theme has a light and a dark version that share HopDrop's
 * navy-tinted neutrals; only the accent family changes. [Wallpaper] follows the phone's wallpaper (Android 12+).
 */
enum class Palette(val key: String, val label: String, val swatch: Color) {
    Ember("ember", "HopDrop", Color(0xFFF2602B)),
    Ocean("ocean", "Ocean", Color(0xFF1F6FD1)),
    Forest("forest", "Forest", Color(0xFF2E8B57)),
    Berry("berry", "Berry", Color(0xFFB03A9C)),
    Midnight("midnight", "Midnight", Color(0xFF2B3A5C)),
    Wallpaper("wallpaper", "Wallpaper", Color(0xFF7C8BA8));

    companion object {
        fun of(key: String?): Palette = entries.firstOrNull { it.key == key } ?: Ember
        val available: List<Palette>
            get() = if (Build.VERSION.SDK_INT >= 31) entries else entries.filter { it != Wallpaper }
    }
}

/** The orange-to-red sweep of the logo, for the few places that carry the brand (logo tile, empty states). */
val BrandSweep = Brush.linearGradient(listOf(Color(0xFFFFB02A), Color(0xFFFF7A2E), Color(0xFFF92B3C)))

/** "Online" green that reads on every theme, light and dark. */
val OnlineGreen = Color(0xFF22A55B)

private data class Accent(
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
)

private val emberLight = Accent(
    Color(0xFFC2410C), Color.White, Color(0xFFFFDCCB), Color(0xFF5C1900),
    Color(0xFF3E4A62), Color.White, Color(0xFFDAE2F5), Color(0xFF131C2F),
    Color(0xFF8B5000), Color.White, Color(0xFFFFDDB5), Color(0xFF2C1600),
)
private val emberDark = Accent(
    Color(0xFFFFB693), Color(0xFF5C1900), Color(0xFF8A3208), Color(0xFFFFDCCB),
    Color(0xFFBEC6DC), Color(0xFF283145), Color(0xFF3E4A62), Color(0xFFDAE2F5),
    Color(0xFFFFB95E), Color(0xFF4A2800), Color(0xFF6A3C00), Color(0xFFFFDDB5),
)
private val oceanLight = Accent(
    Color(0xFF0B5FC2), Color.White, Color(0xFFD6E3FF), Color(0xFF001B3E),
    Color(0xFF545F71), Color.White, Color(0xFFD8E3F8), Color(0xFF111C2B),
    Color(0xFF006A60), Color.White, Color(0xFF74F8E5), Color(0xFF00201C),
)
private val oceanDark = Accent(
    Color(0xFFA9C7FF), Color(0xFF003063), Color(0xFF00468C), Color(0xFFD6E3FF),
    Color(0xFFBCC7DB), Color(0xFF263141), Color(0xFF3C4758), Color(0xFFD8E3F8),
    Color(0xFF53DBC9), Color(0xFF003731), Color(0xFF005048), Color(0xFF74F8E5),
)
private val forestLight = Accent(
    Color(0xFF1B6C3A), Color.White, Color(0xFFA6F4B5), Color(0xFF00210B),
    Color(0xFF50634F), Color.White, Color(0xFFD3E8CF), Color(0xFF0E1F0F),
    Color(0xFF38656C), Color.White, Color(0xFFBCEBF2), Color(0xFF001F24),
)
private val forestDark = Accent(
    Color(0xFF8BD79B), Color(0xFF003918), Color(0xFF005226), Color(0xFFA6F4B5),
    Color(0xFFB7CCB4), Color(0xFF233423), Color(0xFF394B38), Color(0xFFD3E8CF),
    Color(0xFFA0CFD6), Color(0xFF00363C), Color(0xFF1E4D54), Color(0xFFBCEBF2),
)
private val berryLight = Accent(
    Color(0xFF9A2A86), Color.White, Color(0xFFFFD7F1), Color(0xFF3A0033),
    Color(0xFF6D5867), Color.White, Color(0xFFF7DAEC), Color(0xFF261623),
    Color(0xFF82533A), Color.White, Color(0xFFFFDBCB), Color(0xFF321201),
)
private val berryDark = Accent(
    Color(0xFFFFACE8), Color(0xFF5D0053), Color(0xFF7D0E6D), Color(0xFFFFD7F1),
    Color(0xFFDABFD1), Color(0xFF3D2A38), Color(0xFF55404F), Color(0xFFF7DAEC),
    Color(0xFFF6B999), Color(0xFF4C2610), Color(0xFF663C25), Color(0xFFFFDBCB),
)
private val midnightLight = Accent(
    Color(0xFF263452), Color.White, Color(0xFFDAE2F9), Color(0xFF0F1B33),
    Color(0xFF575E71), Color.White, Color(0xFFDCE2F9), Color(0xFF141B2C),
    Color(0xFFC2410C), Color.White, Color(0xFFFFDCCB), Color(0xFF5C1900),
)
private val midnightDark = Accent(
    Color(0xFFB9C6EA), Color(0xFF22304D), Color(0xFF3A4766), Color(0xFFDAE2F9),
    Color(0xFFC0C6DC), Color(0xFF2A3042), Color(0xFF404659), Color(0xFFDCE2F9),
    Color(0xFFFFB693), Color(0xFF5C1900), Color(0xFF8A3208), Color(0xFFFFDCCB),
)

private fun light(a: Accent): ColorScheme = lightColorScheme(
    primary = a.primary, onPrimary = a.onPrimary, primaryContainer = a.primaryContainer, onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.primaryContainer,
    secondary = a.secondary, onSecondary = a.onSecondary, secondaryContainer = a.secondaryContainer, onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary, onTertiary = a.onTertiary, tertiaryContainer = a.tertiaryContainer, onTertiaryContainer = a.onTertiaryContainer,
    background = Color(0xFFF7F8FB), onBackground = Color(0xFF151B27),
    surface = Color(0xFFF7F8FB), onSurface = Color(0xFF151B27),
    surfaceVariant = Color(0xFFE3E7EF), onSurfaceVariant = Color(0xFF485266),
    surfaceTint = a.primary,
    inverseSurface = Color(0xFF2A3140), inverseOnSurface = Color(0xFFEEF1F7),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF737D90), outlineVariant = Color(0xFFC6CCD7), scrim = Color.Black,
    surfaceBright = Color(0xFFF7F8FB), surfaceDim = Color(0xFFD7DBE3),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F8), surfaceContainer = Color(0xFFEBEEF4),
    surfaceContainerHigh = Color(0xFFE5E9F0), surfaceContainerHighest = Color(0xFFDFE3EB),
)

private fun dark(a: Accent): ColorScheme = darkColorScheme(
    primary = a.primary, onPrimary = a.onPrimary, primaryContainer = a.primaryContainer, onPrimaryContainer = a.onPrimaryContainer,
    inversePrimary = a.primaryContainer,
    secondary = a.secondary, onSecondary = a.onSecondary, secondaryContainer = a.secondaryContainer, onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary, onTertiary = a.onTertiary, tertiaryContainer = a.tertiaryContainer, onTertiaryContainer = a.onTertiaryContainer,
    background = Color(0xFF0D121B), onBackground = Color(0xFFE2E6EE),
    surface = Color(0xFF0D121B), onSurface = Color(0xFFE2E6EE),
    surfaceVariant = Color(0xFF3A4252), onSurfaceVariant = Color(0xFFBAC2D0),
    surfaceTint = a.primary,
    inverseSurface = Color(0xFFE2E6EE), inverseOnSurface = Color(0xFF2A3140),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF848EA0), outlineVariant = Color(0xFF3A4252), scrim = Color.Black,
    surfaceBright = Color(0xFF333B49), surfaceDim = Color(0xFF0D121B),
    surfaceContainerLowest = Color(0xFF080C13), surfaceContainerLow = Color(0xFF141A25), surfaceContainer = Color(0xFF181F2B),
    surfaceContainerHigh = Color(0xFF222A37), surfaceContainerHighest = Color(0xFF2D3542),
)

@Composable
fun colorSchemeFor(palette: Palette, dark: Boolean): ColorScheme = when (palette) {
    Palette.Ember -> if (dark) dark(emberDark) else light(emberLight)
    Palette.Ocean -> if (dark) dark(oceanDark) else light(oceanLight)
    Palette.Forest -> if (dark) dark(forestDark) else light(forestLight)
    Palette.Berry -> if (dark) dark(berryDark) else light(berryLight)
    Palette.Midnight -> if (dark) dark(midnightDark) else light(midnightLight)
    Palette.Wallpaper -> if (Build.VERSION.SDK_INT >= 31) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) dark(emberDark) else light(emberLight)
}

val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

private val HopTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold),
        displayMedium = t.displayMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold),
        displaySmall = t.displaySmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = t.titleMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = t.titleSmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = Jakarta, fontSize = 16.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = Jakarta, fontSize = 14.sp),
        bodySmall = t.bodySmall.copy(fontFamily = Jakarta, fontSize = 12.5.sp),
        labelLarge = t.labelLarge.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        labelMedium = t.labelMedium.copy(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        labelSmall = t.labelSmall.copy(fontFamily = Jakarta, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )
}

private val HopShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun HopDropTheme(mode: ThemeMode, palette: Palette, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorSchemeFor(palette, isDark(mode)),
        typography = HopTypography,
        shapes = HopShapes,
        content = content,
    )
}
