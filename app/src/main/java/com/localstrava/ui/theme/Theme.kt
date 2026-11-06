package com.localstrava.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.localstrava.R

/** Pre-dawn palette (spec §4.6). Amber is the only accent; text on amber is [Ink]. */
object RunColors {
    val Ink = Color(0xFF0B1626)
    val Surface = Color(0xFF14243B)
    val Line = Color(0xFF243853)
    val Snow = Color(0xFFF2F5F9)
    val Mist = Color(0xFF8C9DB5)
    val Amber = Color(0xFFFFB000)
}

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** Big readouts: condensed, tabular digits so values don't wobble as they tick. Callers set size/weight. */
val Numbers = TextStyle(fontFamily = BarlowCondensed, fontFeatureSettings = "tnum")

private val colors = darkColorScheme(
    primary = RunColors.Amber,
    onPrimary = RunColors.Ink,
    background = RunColors.Ink,
    onBackground = RunColors.Snow,
    surface = RunColors.Ink,
    onSurface = RunColors.Snow,
    surfaceVariant = RunColors.Surface,
    onSurfaceVariant = RunColors.Mist,
    surfaceContainer = RunColors.Surface,
    outline = RunColors.Line,
    outlineVariant = RunColors.Line,
)

private val typography = Typography().run {
    fun TextStyle.body() = copy(fontFamily = Barlow)
    fun TextStyle.display() = copy(fontFamily = BarlowCondensed)
    Typography(
        displayLarge = displayLarge.display(),
        displayMedium = displayMedium.display(),
        displaySmall = displaySmall.display(),
        headlineLarge = headlineLarge.display(),
        headlineMedium = headlineMedium.display(),
        headlineSmall = headlineSmall.display(),
        titleLarge = titleLarge.body(),
        titleMedium = titleMedium.body(),
        titleSmall = titleSmall.body(),
        bodyLarge = bodyLarge.body(),
        bodyMedium = bodyMedium.body(),
        bodySmall = bodySmall.body(),
        labelLarge = labelLarge.body(),
        labelMedium = labelMedium.body(),
        labelSmall = labelSmall.body(),
    )
}

/** Always dark (owner decision): ignores the system theme on purpose. */
@Composable
fun LocalStravaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
