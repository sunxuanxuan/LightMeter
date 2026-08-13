package com.lightmeter.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeStyle(
    val displayName: String,
    val available: Boolean,
) {
    LIGHT("浅色", true),
    DARK("深色", false),
    HYBRID("混合色", false),
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF9B702B),
    onPrimary = Color(0xFFFFFBF3),
    primaryContainer = Color(0xFFF2E2C3),
    onPrimaryContainer = Color(0xFF38280E),
    secondary = Color(0xFF78644A),
    onSecondary = Color(0xFFFFFBF3),
    secondaryContainer = Color(0xFFECE2D3),
    onSecondaryContainer = Color(0xFF2B251D),
    background = Color(0xFFF4F0E7),
    onBackground = Color(0xFF211E19),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF211E19),
    surfaceVariant = Color(0xFFEEE7DC),
    onSurfaceVariant = Color(0xFF625B51),
    outline = Color(0xFFBDB3A4),
    outlineVariant = Color(0xFFDDD5C8),
    error = Color(0xFFC85743),
    onError = Color.White,
)

@Composable
fun LightMeterTheme(
    themeStyle: AppThemeStyle = AppThemeStyle.LIGHT,
    content: @Composable () -> Unit,
) {
    val colors = when (themeStyle) {
        AppThemeStyle.LIGHT,
        AppThemeStyle.DARK,
        AppThemeStyle.HYBRID,
        -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
