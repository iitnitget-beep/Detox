package com.deeproot.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Default = Typography()

/** Slightly larger body text than the Material defaults for comfortable reading. */
val AppTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Default.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = Default.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Monospaced digits for timers so the text does not jitter while counting. */
val TimerTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 56.sp,
    lineHeight = 64.sp,
)
