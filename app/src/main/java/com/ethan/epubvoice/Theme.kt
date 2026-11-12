package com.ethan.epubvoice

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF2F80FF)
val BlueSoft = Color(0xFF5AA9FF)
val BlueDeep = Color(0xFF0D2B52)
val Ink = Color(0xFF000000)
val Panel = Color(0xFF0D1117)
val Panel2 = Color(0xFF151B24)
val Line = Color(0xFF243247)

fun appColors(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryContainer = BlueDeep,
        onPrimaryContainer = Color.White,
        secondary = BlueSoft,
        onSecondary = Color.Black,
        background = Ink,
        onBackground = Color.White,
        surface = Ink,
        onSurface = Color.White,
        surfaceVariant = Panel2,
        onSurfaceVariant = Color(0xFFB9C6DA),
        outline = Line,
        outlineVariant = Color(0xFF1A2433),
        error = Color(0xFFFF6B6B),
        surfaceContainerLowest = Ink,
        surfaceContainerLow = Panel,
        surfaceContainer = Panel,
        surfaceContainerHigh = Panel2,
        surfaceContainerHighest = Panel2
    )
} else {
    lightColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E6FF),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF1F5FC8),
        background = Color(0xFFEBEBEB),
        onBackground = Color.Black,
        surface = Color(0xFFEBEBEB),
        onSurface = Color.Black,
        surfaceVariant = Color(0xFFDDE3EC),
        onSurfaceVariant = Color(0xFF33445C),
        outline = Color(0xFFA9B8CF),
        outlineVariant = Color(0xFFCBD4E2),
        error = Color(0xFFB3261E),
        surfaceContainerLowest = Color(0xFFF5F5F5),
        surfaceContainerLow = Color(0xFFE2E6EC),
        surfaceContainer = Color(0xFFDCE2EB),
        surfaceContainerHigh = Color(0xFFF4F6FA),
        surfaceContainerHighest = Color(0xFFFFFFFF)
    )
}
