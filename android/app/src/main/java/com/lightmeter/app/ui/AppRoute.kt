package com.lightmeter.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.R
import com.lightmeter.app.settings.SharedPreferencesAppSettingsStore
import com.lightmeter.app.ui.theme.AppThemeStyle

private enum class AppDestination {
    MODE_SELECTION,
    PROFESSIONAL_METERING,
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
            themeStyle = themeStyle,
            onThemeStyleChanged = onThemeStyleChanged,
            onProfessionalMeteringSelected = if (BuildConfig.DEBUG) {
                { destination = AppDestination.PROFESSIONAL_METERING }
            } else {
                null
            },
        )

        AppDestination.PROFESSIONAL_METERING -> MeteringRoute(
            calibrationOffset = appSettings.calibrationOffset,
            onExit = { destination = AppDestination.MODE_SELECTION },
            themeStyle = themeStyle,
            onThemeStyleChanged = onThemeStyleChanged,
        )
    }
}

@Composable
private fun ModeSelectionScreen(
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onProfessionalMeteringSelected: (() -> Unit)?,
) {
    var showsSettings by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, themeStyle) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            val usesLightSystemBars = themeStyle == AppThemeStyle.LIGHT
            controller?.isAppearanceLightStatusBars = usesLightSystemBars
            controller?.isAppearanceLightNavigationBars = usesLightSystemBars
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = HomeBackground,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val compact = maxHeight < 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { showsSettings = true },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(HomeGreen),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (compact) 38.dp else 72.dp))
                Text(
                    text = "一拍即合",
                    color = HomeGreen,
                    fontSize = 36.sp,
                    lineHeight = 42.sp,
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Click & Click",
                    color = HomeInk,
                    fontSize = 19.sp,
                    lineHeight = 26.sp,
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )

            }
        }
    }

    if (showsSettings) {
        HomeSettingsDialog(
            themeStyle = themeStyle,
            onThemeStyleChanged = onThemeStyleChanged,
            onProfessionalMeteringSelected = onProfessionalMeteringSelected,
            onDismiss = { showsSettings = false },
        )
    }
}

@Composable
private fun HomeFeatureCard(
    imageResource: Int,
    title: String,
    description: String,
    containerColor: Color,
    contentColor: Color,
    secondaryContentColor: Color,
    arrowContainerColor: Color,
    arrowContentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(imageResource),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(0.43f)
                    .fillMaxHeight()
                    .padding(end = 12.dp),
            )
            Column(
                modifier = Modifier
                    .weight(0.57f)
                    .fillMaxHeight(),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = title,
                    color = contentColor,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    letterSpacing = 0.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    color = secondaryContentColor,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    letterSpacing = 0.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Spacer(modifier = Modifier.weight(0.7f))
                Surface(
                    color = arrowContainerColor,
                    contentColor = arrowContentColor,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.End)
                        .size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSettingsDialog(
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onProfessionalMeteringSelected: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "界面主题",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeStyle.entries
                        .filter(AppThemeStyle::available)
                        .forEach { style ->
                            FilterChip(
                                selected = themeStyle == style,
                                onClick = { onThemeStyleChanged(style) },
                                label = { Text(style.displayName) },
                            )
                        }
                }
                onProfessionalMeteringSelected?.let { onSelected ->
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            onDismiss()
                            onSelected()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("专业测光")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
    )
}

private val HomeBackground = Color(0xFFF7F8F4)
private val HomeGreen = Color(0xFF123C30)
private val HomeInk = Color(0xFF12251F)
private val HomeMutedInk = Color(0xFF53645E)
private val HomeSecondaryCard = Color(0xFFE9EEEA)

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
