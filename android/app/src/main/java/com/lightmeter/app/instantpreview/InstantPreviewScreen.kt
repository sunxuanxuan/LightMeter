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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.MoreHoriz
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
    val selectedFilm = filmOptions.first { it.id == selectedFilmId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            InstantTopControls(
                onBack = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val availableAspectRatio = if (maxHeight.value > 0f) {
                    maxWidth.value / maxHeight.value
                } else {
                    PREVIEW_ASPECT_RATIO
                }
                val previewModifier = if (availableAspectRatio > PREVIEW_ASPECT_RATIO) {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(PREVIEW_ASPECT_RATIO)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(PREVIEW_ASPECT_RATIO)
                }
                val previewShape = RoundedCornerShape(8.dp)

                Box(
                    modifier = previewModifier
                        .background(Color.Black, previewShape)
                        .clip(previewShape),
                ) {
                    InstantFilmViewfinder(
                        frameResource = selectedFilm.frameResource,
                        hasCameraPermission = hasCameraPermission,
                        cameraSessionId = cameraSessionId,
                        onRequestPermission = onRequestPermission,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            InstantRiskPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )

            InstantBottomBar(
                cameraImageResource = cameraOptions
                    .first { it.id == selectedCameraId }
                    .imageResource,
                filmImageResource = filmOptions
                    .first { it.id == selectedFilmId }
                    .imageResource,
                captureEnabled = hasCameraPermission,
                onCameraClick = { selector = InstantSelector.CAMERA },
                onFilmClick = { selector = InstantSelector.FILM },
            )
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
private fun InstantTopControls(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(
            onClick = onBack,
            contentDescription = "返回",
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
            )
        }
        RoundIconButton(
            onClick = {},
            contentDescription = "设置",
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                contentDescription = null,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}

@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(11.dp),
            ) {
                content()
            }
        }
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
private fun InstantRiskPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "曝光建议将在这里显示",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun InstantBottomBar(
    cameraImageResource: Int,
    filmImageResource: Int,
    captureEnabled: Boolean,
    onCameraClick: () -> Unit,
    onFilmClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstantPresetButton(
                imageResource = cameraImageResource,
                label = "机型",
                onClick = onCameraClick,
                modifier = Modifier.weight(1f),
            )
            InstantCaptureButton(
                enabled = captureEnabled,
                onClick = {},
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            InstantPresetButton(
                imageResource = filmImageResource,
                label = "相纸",
                onClick = onFilmClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InstantPresetButton(
    imageResource: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(imageResource),
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(42.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .size(75.dp)
            .background(CAPTURE_RED, CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled && !isPressed) {
                isPressed = true
            },
    ) {
        Box(
            modifier = Modifier
                .size(53.dp)
                .scale(innerScale)
                .background(Color.White, CircleShape),
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
private val CAPTURE_RED = Color(0xFFD84343)
