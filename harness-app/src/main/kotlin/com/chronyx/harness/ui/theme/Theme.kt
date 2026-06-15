package com.chronyx.harness.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Operational Materialism palette. Matte near-blacks, greyscale data, ONE earned accent. No
 * gradients, no glassmorphism. The accent ([Phosphor]) is used ONLY for earned live state
 * (SYNC LOCKED / ARMED / RECORDING) — never decoratively.
 */
object ChronyxColors {
    val Surface = Color(0xFF0A0A0A)
    val SurfaceRaised = Color(0xFF111111)
    val Hairline = Color(0xFF1E1E1E)
    val TextPrimary = Color(0xFFE6E6E6)
    val TextSecondary = Color(0xFF8A8A8A)
    val TextDim = Color(0xFF5A5A5A)
    val Phosphor = Color(0xFF2EE6A6) // the single earned accent
    val Alarm = Color(0xFFE6552E)    // reserved for hard fault / space-exhausted states only
}

/** Every datum is monospace. */
private val Mono = FontFamily.Monospace

private val ChronyxTypography = Typography(
    titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp),
    bodyLarge = TextStyle(fontFamily = Mono, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Mono, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.5.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp),
)

private val ChronyxScheme = darkColorScheme(
    primary = ChronyxColors.Phosphor,
    onPrimary = ChronyxColors.Surface,
    background = ChronyxColors.Surface,
    onBackground = ChronyxColors.TextPrimary,
    surface = ChronyxColors.SurfaceRaised,
    onSurface = ChronyxColors.TextPrimary,
    error = ChronyxColors.Alarm,
)

@Composable
fun ChronyxTheme(content: @Composable () -> Unit) {
    // Always dark; the design system is fixed regardless of system setting.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = ChronyxScheme, typography = ChronyxTypography, content = content)
}
