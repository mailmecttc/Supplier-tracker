package app.suppliertracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.suppliertracker.model.Status

private val Brand = Color(0xFF1F4E79)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E4F5),
    onPrimaryContainer = Color(0xFF0B2540),
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFF4F5F7),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC8F2),
    onPrimary = Color(0xFF0B2540),
    primaryContainer = Color(0xFF1D3247),
    onPrimaryContainer = Color(0xFFD5E4F5),
)

/** Red / amber / green are used only for status. */
@Immutable
data class StatusPalette(
    val ok: Color, val okBg: Color, val warn: Color, val warnBg: Color, val bad: Color, val badBg: Color, val neutral: Color, val neutralBg: Color,
    /** Amber for bars and box borders (brighter than the amber used for text). */
    val warnFill: Color,
) {
    fun fg(s: Status) = when (s) {
        Status.ON_TRACK, Status.AHEAD -> ok
        Status.WATCH -> warn
        Status.BEHIND -> bad
        else -> neutral
    }

    fun bg(s: Status) = when (s) {
        Status.ON_TRACK, Status.AHEAD -> okBg
        Status.WATCH -> warnBg
        Status.BEHIND -> badBg
        else -> neutralBg
    }

    /** Colour for a filled bar: green on schedule, amber slightly behind, red behind, grey when there is nothing to judge. */
    fun fill(s: Status) = when (s) {
        Status.ON_TRACK, Status.AHEAD -> ok
        Status.WATCH -> warnFill
        Status.BEHIND -> bad
        else -> neutral
    }
}

private val LightStatus = StatusPalette(
    ok = Color(0xFF1A7F4B), okBg = Color(0xFFE2F4EA), warn = Color(0xFF9A6400), warnBg = Color(0xFFFDF1D8),
    bad = Color(0xFFC0352B), badBg = Color(0xFFFBE5E3), neutral = Color(0xFF5D6877), neutralBg = Color(0xFFE6E9EE),
    warnFill = Color(0xFFD08C00),
)
private val DarkStatus = StatusPalette(
    ok = Color(0xFF4CC38A), okBg = Color(0xFF143324), warn = Color(0xFFE6B04A), warnBg = Color(0xFF3A2E12),
    bad = Color(0xFFF07368), badBg = Color(0xFF3D1C1A), neutral = Color(0xFF9AA7B6), neutralBg = Color(0xFF26303B),
    warnFill = Color(0xFFE6B04A),
)

val LocalStatusPalette = staticCompositionLocalOf { LightStatus }

/** [dark]: from Settings (or the phone). [phoneColours]: wallpaper colours on Android 12+, else the app's blue. */
@Composable
fun SupplierTheme(dark: Boolean = isSystemInDarkTheme(), phoneColours: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        phoneColours && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusPalette provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
