package com.kenny.tellofollow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Outdoor FPV-oriented dark palette — high contrast, low glare
private val FpvGreen = Color(0xFF00E5A0)
private val FpvWarn = Color(0xFFFFB300)
private val FpvDanger = Color(0xFFFF1744)
private val FpvBg = Color(0xFF0A0A0A)
private val FpvSurface = Color(0xFF141414)
private val FpvOnBg = Color(0xFFE8E8E8)

private val DarkColors = darkColorScheme(
    primary = FpvGreen,
    onPrimary = Color.Black,
    secondary = FpvWarn,
    onSecondary = Color.Black,
    error = FpvDanger,
    onError = Color.White,
    background = FpvBg,
    onBackground = FpvOnBg,
    surface = FpvSurface,
    onSurface = FpvOnBg,
)

@Composable
fun TelloFollowTheme(
    darkTheme: Boolean = true, // always dark for FPV
    content: @Composable () -> Unit,
) {
    // Ignore system light theme — outdoor FPV needs dark chrome
    @Suppress("UNUSED_VARIABLE")
    val ignored = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
