package com.localstrava.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private val palette = setOf(
        RunColors.Ink, RunColors.Surface, RunColors.Line, RunColors.Snow, RunColors.Mist, RunColors.Amber,
    )

    // Dialogs and the date/time pickers draw with these roles; Material's baseline purples must not leak.
    @Test fun dialogAndPickerRolesUsePalette() {
        val s = RunColorScheme
        listOf(
            "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
            "secondary" to s.secondary, "onSecondary" to s.onSecondary,
            "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
            "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
            "tertiaryContainer" to s.tertiaryContainer, "onTertiaryContainer" to s.onTertiaryContainer,
            "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
            "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim, "surfaceTint" to s.surfaceTint,
        ).forEach { (role, color) -> assertTrue("$role is off-palette: $color", color in palette) }
    }
}
