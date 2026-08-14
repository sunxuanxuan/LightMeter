package com.lightmeter.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lightmeter.app.filmpreview.FilmPreviewRoute
import com.lightmeter.app.settings.SharedPreferencesAppSettingsStore
import com.lightmeter.app.ui.theme.AppThemeStyle

private enum class AppDestination {
    MODE_SELECTION,
    PROFESSIONAL,
    FILM_PREVIEW,
}

@Composable
fun AppRoute(
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember(context) {
        SharedPreferencesAppSettingsStore(context.applicationContext)
    }
    var destination by rememberSaveable {
        mutableStateOf(AppDestination.MODE_SELECTION)
    }
    val appSettings = remember(settingsStore, destination) {
        settingsStore.load()
    }
    when (destination) {
        AppDestination.MODE_SELECTION -> ModeSelectionScreen(
            onProfessionalSelected = {
                destination = AppDestination.PROFESSIONAL
            },
            onFilmPreviewSelected = { destination = AppDestination.FILM_PREVIEW },
        )

        AppDestination.PROFESSIONAL -> {
            BackHandler {
                destination = AppDestination.MODE_SELECTION
            }
            MeteringRoute(
                calibrationOffset = appSettings.calibrationOffset,
                onExit = { destination = AppDestination.MODE_SELECTION },
                themeStyle = themeStyle,
                onThemeStyleChanged = onThemeStyleChanged,
            )
        }

        AppDestination.FILM_PREVIEW -> FilmPreviewRoute(
            onExit = { destination = AppDestination.MODE_SELECTION },
            calibrationOffset = appSettings.calibrationOffset,
            themeStyle = themeStyle,
            onThemeStyleChanged = onThemeStyleChanged,
        )
    }
}

@Composable
private fun ModeSelectionScreen(
    onProfessionalSelected: () -> Unit,
    onFilmPreviewSelected: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "FilmLightMeter",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "体验胶片摄影魅力",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 32.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ModeCard(
                    symbol = "▣",
                    title = "胶片预览",
                    description = "固定参数下的曝光效果与宽容度风险",
                    onClick = onFilmPreviewSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
                ModeCard(
                    symbol = "◎",
                    title = "专业测光",
                    description = "完整测光、曝光组合与画幅控制",
                    onClick = onProfessionalSelected,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    symbol: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 116.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Text(
                text = "进入  →",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
