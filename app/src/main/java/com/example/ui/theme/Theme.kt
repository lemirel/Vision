package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VisionTrackDarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    onPrimary = Color(0xFF003830),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = NeonCyanLight,
    secondary = TargetAmber,
    onSecondary = Color(0xFF432C00),
    secondaryContainer = Color(0xFF604100),
    onSecondaryContainer = TargetAmberLight,
    tertiary = RecCrimson,
    onTertiary = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCardDark,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    outlineVariant = SurfaceElevatedDark
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use our signature cyber-optics dark theme
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VisionTrackDarkColorScheme,
        typography = Typography,
        content = content
    )
}
