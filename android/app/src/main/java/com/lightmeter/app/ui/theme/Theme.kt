package com.lightmeter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5B567),
    onPrimary = Color(0xFF241A0A),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF171717),
    onSurface = Color.White,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF745B22),
    background = Color(0xFFF6F3EC),
    onBackground = Color(0xFF1D1B16),
    surface = Color.White,
    onSurface = Color(0xFF1D1B16),
)

@Composable
fun LightMeterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
