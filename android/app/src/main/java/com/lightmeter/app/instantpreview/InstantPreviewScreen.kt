package com.lightmeter.app.instantpreview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lightmeter.app.R
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.ui.CameraPreviewView
import kotlinx.coroutines.delay

private enum class InstantSelector {
    CAMERA,
    FILM,
}

private data class InstantCameraOption(
    val id: String,
    val name: String,
    val imageResource: Int,
)

private data class InstantFilmOption(
    val id: String,
    val name: String,
    val iso: Int,
    val imageResource: Int,
    val frameResource: Int,
)

private val cameraOptions = listOf(
    InstantCameraOption(
        id = "fujifilm-instax-mini-12",
        name = "Fujifilm instax mini 12",
        imageResource = R.drawable.preset_instax_mini_12,
    ),
)

private val filmOptions = listOf(
    InstantFilmOption(
        id = "instax-mini-white",
        name = "instax mini 白框相纸",
        iso = 800,
        imageResource = R.drawable.instant_film_white,
        frameResource = R.drawable.instant_frame_white,
    ),
    InstantFilmOption(
        id = "instax-mini-black",
        name = "instax mini 黑框相纸",
        iso = 800,
        imageResource = R.drawable.instant_film_black,
        frameResource = R.drawable.instant_frame_black,
    ),
)

@Composable
fun InstantPreviewRoute(onExit: () -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var cameraSessionId by rememberSaveable { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) cameraSessionId += 1
    }

    BackHandler(onBack = onExit)

    InstantPreviewWorkspace(
        hasCameraPermission = hasCameraPermission,
        cameraSessionId = cameraSessionId,
        onBack = onExit,
        onRequestPermission = {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
    )
}

@Composable
private fun InstantPreviewWorkspace(
    hasCameraPermission: Boolean,
    cameraSessionId: Int,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    var selector by rememberSaveable { mutableStateOf<InstantSelector?>(null) }
    var selectedCameraId by rememberSaveable { mutableStateOf(cameraOptions.first().id) }
    var selectedFilmId by rememberSaveable { mutableStateOf(filmOptions.first().id) }
    val selectedCamera = cameraOptions.first { it.id == selectedCameraId }
    val selectedFilm = filmOptions.first { it.id == selectedFilmId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(INSTANT_PANEL_COLOR),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            InstantTopControls(
                onBack = onBack,
                onOpenSettings = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .background(INSTANT_TOOLBAR_GREEN)
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(INSTANT_PANEL_COLOR),
            ) {
                val compact = maxHeight < 650.dp
                val controlPanelHeight = if (compact) 184.dp else 196.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(INSTANT_TOOLBAR_GREEN),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(PREVIEW_ASPECT_RATIO)
                        .offset(y = (-1).dp)
                        .background(Color.Black),
                ) {
                    InstantFilmViewfinder(
                        frameResource = selectedFilm.frameResource,
                        hasCameraPermission = hasCameraPermission,
                        cameraSessionId = cameraSessionId,
                        onRequestPermission = onRequestPermission,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                InstantControlPanel(
                    cameraImageResource = selectedCamera.imageResource,
                    filmImageResource = selectedFilm.imageResource,
                    captureEnabled = hasCameraPermission,
                    onCameraClick = { selector = InstantSelector.CAMERA },
                    onFilmClick = { selector = InstantSelector.FILM },
                    compact = compact,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(controlPanelHeight),
                )
            }
        }

        selector?.let { activeSelector ->
            InstantSelectorSheet(
                selector = activeSelector,
                selectedCameraId = selectedCameraId,
                selectedFilmId = selectedFilmId,
                onDismiss = { selector = null },
                onCameraSelected = {
                    selectedCameraId = it
                    selector = null
                },
                onFilmSelected = {
                    selectedFilmId = it
                    selector = null
                },
            )
        }
    }
}

@Composable
private fun InstantControlPanel(
    cameraImageResource: Int,
    filmImageResource: Int,
    captureEnabled: Boolean,
    onCameraClick: () -> Unit,
    onFilmClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = INSTANT_PANEL_COLOR,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(
                    start = 18.dp,
                    top = if (compact) 14.dp else 18.dp,
                    end = 18.dp,
                    bottom = 12.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InstantSelectionTile(
                    imageResource = cameraImageResource,
                    contentDescription = "选择拍立得机型",
                    onClick = onCameraClick,
                )
                InstantCaptureButton(
                    enabled = captureEnabled,
                    onClick = {},
                )
                InstantSelectionTile(
                    imageResource = filmImageResource,
                    contentDescription = "选择拍立得相纸",
                    onClick = onFilmClick,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            InstantEnvironmentPill(
                text = "当前光线：分析中",
            )
        }
    }
}

@Composable
private fun InstantSelectionTile(
    imageResource: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(72.dp)
            .clickable(onClick = onClick),
        color = INSTANT_SELECTOR_COLOR,
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Image(
                painter = painterResource(imageResource),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun InstantEnvironmentPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(38.dp),
        color = INSTANT_LIGHT_STATUS_COLOR,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WbSunny,
                contentDescription = null,
                tint = INSTANT_SUN_COLOR,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                color = INSTANT_TEXT_COLOR,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InstantTopControls(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InstantToolbarAction(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "返回",
            onClick = onBack,
        )
        InstantToolbarAction(
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun InstantToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .size(width = 64.dp, height = 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun InstantFilmViewfinder(
    frameResource: Int,
    hasCameraPermission: Boolean,
    cameraSessionId: Int,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
    ) {
        val imageWidth = maxWidth * IMAGE_WIDTH_FRACTION
        val imageHeight = maxHeight * IMAGE_HEIGHT_FRACTION
        val imageTop = maxHeight * IMAGE_TOP_FRACTION

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = imageTop)
                .size(width = imageWidth, height = imageHeight)
                .clip(RoundedCornerShape(2.dp)),
        ) {
            if (hasCameraPermission) {
                key(cameraSessionId) {
                    CameraPreviewView(
                        meteringConfig = MeteringConfig(
                            mode = MeteringMode.AVERAGE,
                            previewAspectRatio = IMAGE_ASPECT_RATIO.toDouble(),
                        ),
                        targetZoomRatio = 1f,
                        freezeRequestId = 0,
                        shouldCaptureFrame = false,
                        onMeteringResult = {},
                        onFrameCaptured = {},
                        onOpticsAvailable = {},
                        onZoomStateChanged = {},
                        onReady = {},
                        onError = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                CameraPermissionPlaceholder(
                    onRequestPermission = onRequestPermission,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Image(
            painter = painterResource(frameResource),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CameraPermissionPlaceholder(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Button(onClick = onRequestPermission) {
            Text("授予相机权限")
        }
    }
}

@Composable
private fun InstantCaptureButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val innerScale by animateFloatAsState(
        targetValue = if (isPressed) 0.66f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "instantCaptureScale",
    )

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(140L)
            isPressed = false
            onClick()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(INSTANT_CONTROL_BUTTON_SIZE)
            .alpha(if (enabled) 1f else 0.45f)
            .background(INSTANT_SHUTTER_RED, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled && !isPressed) {
                isPressed = true
            },
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .background(Color.White, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(68.dp)
                .scale(innerScale)
                .background(INSTANT_SHUTTER_RED, CircleShape),
        )
    }
}

@Composable
private fun InstantSelectorSheet(
    selector: InstantSelector,
    selectedCameraId: String,
    selectedFilmId: String,
    onDismiss: () -> Unit,
    onCameraSelected: (String) -> Unit,
    onFilmSelected: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(onClick = {}),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 22.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(2.dp),
                        ),
                )
                Text(
                    text = if (selector == InstantSelector.CAMERA) {
                        "选择机型"
                    } else {
                        "选择相纸"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(
                        start = 22.dp,
                        top = 16.dp,
                        bottom = 12.dp,
                    ),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (selector == InstantSelector.CAMERA) {
                        items(cameraOptions, key = { it.id }) { option ->
                            InstantSelectorOption(
                                name = option.name,
                                imageResource = option.imageResource,
                                detail = null,
                                selected = option.id == selectedCameraId,
                                onClick = { onCameraSelected(option.id) },
                            )
                        }
                    } else {
                        items(filmOptions, key = { it.id }) { option ->
                            InstantSelectorOption(
                                name = option.name,
                                imageResource = option.imageResource,
                                detail = "ISO ${option.iso}",
                                selected = option.id == selectedFilmId,
                                onClick = { onFilmSelected(option.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstantSelectorOption(
    name: String,
    imageResource: Int,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(width = 144.dp, height = 142.dp)
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(imageResource),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

private const val PREVIEW_ASPECT_RATIO = 54f / 86f
private const val IMAGE_ASPECT_RATIO = 46f / 62f
private const val IMAGE_WIDTH_FRACTION = 46f / 54f
private const val IMAGE_HEIGHT_FRACTION = 62f / 86f
private const val IMAGE_TOP_FRACTION = 7f / 86f
private val INSTANT_CONTROL_BUTTON_SIZE = 88.dp
private val INSTANT_TOOLBAR_GREEN = Color(0xFF10372C)
private val INSTANT_PANEL_COLOR = Color(0xFFFBFCF9)
private val INSTANT_SELECTOR_COLOR = Color(0xFFF0F2EF)
private val INSTANT_LIGHT_STATUS_COLOR = Color(0xFFF8F3E7)
private val INSTANT_TEXT_COLOR = Color(0xFF17251F)
private val INSTANT_SUN_COLOR = Color(0xFFD3A52B)
private val INSTANT_SHUTTER_RED = Color(0xFFE45649)
