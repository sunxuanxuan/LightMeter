package com.lightmeter.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppThemeStyle(
    val displayName: String,
    val available: Boolean,
) {
    LIGHT("浅色", true),
    DARK("深色", true),
    HYBRID("混合色", false),
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A2A),
    onPrimary = Color(0xFF211000),
    primaryContainer = Color(0xFF4A2D19),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFF5AA9FF),
    onSecondary = Color(0xFF002E52),
    secondaryContainer = Color(0xFF173C5D),
    onSecondaryContainer = Color(0xFFD2E8FF),
    background = Color(0xFF0D0D0F),
    onBackground = Color(0xFFF4F1ED),
    surface = Color(0xFF171719),
    onSurface = Color(0xFFF4F1ED),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFFB7B3BB),
    outline = Color(0xFF716E75),
    outlineVariant = Color(0xFF3A393D),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF3B0805),
)

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
    themeStyle: AppThemeStyle = AppThemeStyle.DARK,
    content: @Composable () -> Unit,
) {
    val colors = when (themeStyle) {
        AppThemeStyle.LIGHT -> LightColors
        AppThemeStyle.DARK -> DarkColors
        AppThemeStyle.HYBRID -> DarkColors
    }
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
