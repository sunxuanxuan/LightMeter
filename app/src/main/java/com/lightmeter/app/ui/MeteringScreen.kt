package com.lightmeter.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightmeter.app.R
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.exposure.ExposurePair
import com.lightmeter.app.exposure.FramePreset
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.NormalizedPoint
import com.lightmeter.app.metering.MeteringResult
import kotlin.math.abs
import kotlin.math.min

@Composable
fun MeteringRoute(
    viewModel: MeteringViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val permissionState = when {
            granted -> CameraPermissionState.GRANTED
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CAMERA,
            ) -> CameraPermissionState.DENIED
            hasRequestedPermission -> CameraPermissionState.PERMANENTLY_DENIED
            else -> CameraPermissionState.DENIED
        }
        viewModel.updatePermission(permissionState)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.updatePermission(
            if (granted) CameraPermissionState.GRANTED else CameraPermissionState.DENIED,
        )
    }

    MeteringScreen(
        state = state,
        onRequestPermission = {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onOpenSettings = { context.openAppSettings() },
        onCameraReady = viewModel::onCameraReady,
        onCameraOpticsAvailable = viewModel::onCameraOpticsAvailable,
        onCameraError = viewModel::onCameraError,
        onMeteringResult = viewModel::onMeteringResult,
        onSpotSelected = viewModel::selectSpot,
        onAverageMetering = viewModel::useAverageMetering,
        onIsoSelected = viewModel::selectIso,
        onFramePresetSelected = viewModel::selectFramePreset,
        onExposureCompensationChanged = viewModel::adjustExposureCompensation,
        onExposureStep = viewModel::stepExposurePair,
        onFreezePreview = viewModel::freezePreview,
        onResumeLive = viewModel::resumeLivePreview,
        onFreezeCaptureFailed = viewModel::onFreezeCaptureFailed,
    )
}

@Composable
private fun MeteringScreen(
    state: MeteringUiState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onCameraReady: () -> Unit,
    onCameraOpticsAvailable: (CameraOptics) -> Unit,
    onCameraError: (Throwable) -> Unit,
    onMeteringResult: (MeteringResult) -> Unit,
    onSpotSelected: (NormalizedPoint) -> Unit,
    onAverageMetering: () -> Unit,
    onIsoSelected: (Int) -> Unit,
    onFramePresetSelected: (FramePreset) -> Unit,
    onExposureCompensationChanged: (Double) -> Unit,
    onExposureStep: (Int) -> Unit,
    onFreezePreview: () -> Unit,
    onResumeLive: () -> Unit,
    onFreezeCaptureFailed: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (state.permissionState) {
            CameraPermissionState.GRANTED -> CameraContent(
                state = state,
                onCameraReady = onCameraReady,
                onCameraOpticsAvailable = onCameraOpticsAvailable,
                onCameraError = onCameraError,
                onMeteringResult = onMeteringResult,
                onSpotSelected = onSpotSelected,
                onAverageMetering = onAverageMetering,
                onIsoSelected = onIsoSelected,
                onFramePresetSelected = onFramePresetSelected,
                onExposureCompensationChanged = onExposureCompensationChanged,
                onExposureStep = onExposureStep,
                onFreezePreview = onFreezePreview,
                onResumeLive = onResumeLive,
                onFreezeCaptureFailed = onFreezeCaptureFailed,
            )

            CameraPermissionState.PERMANENTLY_DENIED -> PermissionContent(
                buttonText = stringResource(R.string.open_settings),
                onClick = onOpenSettings,
            )

            CameraPermissionState.UNKNOWN,
            CameraPermissionState.DENIED,
            -> PermissionContent(
                buttonText = stringResource(R.string.grant_camera_permission),
                onClick = onRequestPermission,
            )
        }
    }
}

@Composable
private fun CameraContent(
    state: MeteringUiState,
    onCameraReady: () -> Unit,
    onCameraOpticsAvailable: (CameraOptics) -> Unit,
    onCameraError: (Throwable) -> Unit,
    onMeteringResult: (MeteringResult) -> Unit,
    onSpotSelected: (NormalizedPoint) -> Unit,
    onAverageMetering: () -> Unit,
    onIsoSelected: (Int) -> Unit,
    onFramePresetSelected: (FramePreset) -> Unit,
    onExposureCompensationChanged: (Double) -> Unit,
    onExposureStep: (Int) -> Unit,
    onFreezePreview: () -> Unit,
    onResumeLive: () -> Unit,
    onFreezeCaptureFailed: () -> Unit,
) {
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(state.isFrozen) {
        if (!state.isFrozen) {
            frozenFrame = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.05f),
        ) {
            CameraPreviewView(
                meteringConfig = MeteringConfig(
                    mode = state.meteringMode,
                    spotPoint = state.spotMeteringPoint,
                    calibrationOffset = state.calibrationOffset,
                ),
                freezeRequestId = state.freezeRequestId,
                shouldCaptureFrame = state.isFrozen,
                onMeteringResult = onMeteringResult,
                onFrameCaptured = { bitmap ->
                    if (bitmap == null) {
                        onFreezeCaptureFailed()
                    } else {
                        frozenFrame = bitmap
                    }
                },
                onOpticsAvailable = onCameraOpticsAvailable,
                onReady = onCameraReady,
                onError = onCameraError,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.isFrozen) {
                frozenFrame?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "定格测光画面",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            ViewfinderOverlay(
                state = state,
                enabled = !state.isFrozen,
                onSpotSelected = onSpotSelected,
                modifier = Modifier.fillMaxSize(),
            )

            ExposureScaleOverlay(
                exposures = state.equivalentExposures,
                primaryExposure = state.primaryExposure,
                onExposureStep = onExposureStep,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 56.dp),
            )

            CaptureControls(
                isFrozen = state.isFrozen,
                canFreeze = state.isCameraReady && state.ev100Metered != null,
                meteringMode = state.meteringMode,
                onFreezePreview = onFreezePreview,
                onResumeLive = onResumeLive,
                onAverageMetering = onAverageMetering,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }

        ExposurePanel(
            state = state,
            onIsoSelected = onIsoSelected,
            onFramePresetSelected = onFramePresetSelected,
            onExposureCompensationChanged = onExposureCompensationChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.95f),
        )
    }
}

@Composable
private fun ViewfinderOverlay(
    state: MeteringUiState,
    enabled: Boolean,
    onSpotSelected: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionModifier = if (enabled) {
        Modifier.pointerInput(state.framePreset, state.cameraOptics) {
            detectTapGestures { offset ->
                if (size.width == 0 || size.height == 0) return@detectTapGestures
                val frame = calculateViewfinderRect(
                    viewWidth = size.width.toFloat(),
                    viewHeight = size.height.toFloat(),
                    preset = state.framePreset,
                    optics = state.cameraOptics,
                )
                if (!frame.contains(offset)) return@detectTapGestures
                onSpotSelected(
                    NormalizedPoint(
                        x = (offset.x / size.width).toDouble().coerceIn(0.0, 1.0),
                        y = (offset.y / size.height).toDouble().coerceIn(0.0, 1.0),
                    ),
                )
            }
        }
    } else {
        Modifier
    }
    Canvas(
        modifier = modifier.then(interactionModifier),
    ) {
        val frame = calculateViewfinderRect(
            viewWidth = size.width,
            viewHeight = size.height,
            preset = state.framePreset,
            optics = state.cameraOptics,
        )
        val maskColor = Color.Black.copy(alpha = 0.58f)
        drawRect(
            color = maskColor,
            size = Size(size.width, frame.top),
        )
        drawRect(
            color = maskColor,
            topLeft = Offset(0f, frame.bottom),
            size = Size(size.width, size.height - frame.bottom),
        )
        drawRect(
            color = maskColor,
            topLeft = Offset(0f, frame.top),
            size = Size(frame.left, frame.height),
        )
        drawRect(
            color = maskColor,
            topLeft = Offset(frame.right, frame.top),
            size = Size(size.width - frame.right, frame.height),
        )
        drawRect(
            color = Color.White.copy(alpha = 0.86f),
            topLeft = frame.topLeft,
            size = frame.size,
            style = Stroke(width = 1.dp.toPx()),
        )

        state.spotMeteringPoint?.let { point ->
            drawCircle(
                color = Color.White,
                radius = min(size.width, size.height) * 0.05f,
                center = Offset(
                    x = size.width * point.x.toFloat(),
                    y = size.height * point.y.toFloat(),
                ),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

private fun calculateViewfinderRect(
    viewWidth: Float,
    viewHeight: Float,
    preset: FramePreset,
    optics: CameraOptics?,
): Rect {
    val camera = optics ?: CameraOptics(
        sensorWidthMm = 36.0,
        sensorHeightMm = 24.0,
        focalLengthMm = 24.0,
    )
    val sensorPortraitWidth = min(camera.sensorWidthMm, camera.sensorHeightMm)
    val sensorPortraitHeight = maxOf(camera.sensorWidthMm, camera.sensorHeightMm)
    val viewAspectRatio = viewWidth / viewHeight
    val sensorAspectRatio = sensorPortraitWidth / sensorPortraitHeight

    val displayedSensorWidth: Double
    val displayedSensorHeight: Double
    if (viewAspectRatio >= sensorAspectRatio) {
        displayedSensorWidth = sensorPortraitWidth
        displayedSensorHeight = sensorPortraitWidth / viewAspectRatio
    } else {
        displayedSensorHeight = sensorPortraitHeight
        displayedSensorWidth = sensorPortraitHeight * viewAspectRatio
    }

    val targetPortraitWidth = min(preset.frameWidthMm, preset.frameHeightMm)
    val targetPortraitHeight = maxOf(preset.frameWidthMm, preset.frameHeightMm)
    val targetProjectionWidth =
        camera.focalLengthMm * targetPortraitWidth / preset.focalLengthMm
    val targetProjectionHeight =
        camera.focalLengthMm * targetPortraitHeight / preset.focalLengthMm
    val widthFraction = (targetProjectionWidth / displayedSensorWidth).coerceIn(0.0, 1.0)
    val heightFraction = (targetProjectionHeight / displayedSensorHeight).coerceIn(0.0, 1.0)
    val frameWidth = (viewWidth * widthFraction).toFloat()
    val frameHeight = (viewHeight * heightFraction).toFloat()
    val left = (viewWidth - frameWidth) / 2f
    val top = (viewHeight - frameHeight) / 2f

    return Rect(
        left = left,
        top = top,
        right = left + frameWidth,
        bottom = top + frameHeight,
    )
}

@Composable
private fun CaptureControls(
    isFrozen: Boolean,
    canFreeze: Boolean,
    meteringMode: MeteringMode,
    onFreezePreview: () -> Unit,
    onResumeLive: () -> Unit,
    onAverageMetering: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isFrozen && meteringMode == MeteringMode.SPOT) {
            SymbolButton(
                symbol = "◎",
                onClick = onAverageMetering,
                enabled = true,
            )
        }
        SymbolButton(
            symbol = if (isFrozen) "▶" else "⏸",
            onClick = if (isFrozen) onResumeLive else onFreezePreview,
            enabled = isFrozen || canFreeze,
        )
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = Color.White.copy(alpha = 0.35f),
            disabledContentColor = Color.Black.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(52.dp),
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ExposureScaleOverlay(
    exposures: List<ExposurePair>,
    primaryExposure: ExposurePair?,
    onExposureStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = primaryExposure
        ?.let(exposures::indexOf)
        ?.takeIf { it >= 0 }
        ?: return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposureSideScale(
            title = "光圈",
            exposures = exposures,
            selectedIndex = selectedIndex,
            value = ExposurePair::apertureLabel,
            alignEnd = false,
            onExposureStep = onExposureStep,
        )
        ExposureSideScale(
            title = "快门",
            exposures = exposures,
            selectedIndex = selectedIndex,
            value = ExposurePair::shutterLabel,
            alignEnd = true,
            onExposureStep = onExposureStep,
        )
    }
}

@Composable
private fun ExposureSideScale(
    title: String,
    exposures: List<ExposurePair>,
    selectedIndex: Int,
    value: (ExposurePair) -> String,
    alignEnd: Boolean,
    onExposureStep: (Int) -> Unit,
) {
    var dragOffset by remember { mutableStateOf(0f) }

    Surface(
        color = Color.Black.copy(alpha = 0.54f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .width(82.dp)
            .pointerInput(exposures, selectedIndex) {
                var accumulatedDrag = 0f
                val stepThreshold = 28.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulatedDrag = 0f
                        dragOffset = 0f
                    },
                    onDragCancel = {
                        accumulatedDrag = 0f
                        dragOffset = 0f
                    },
                    onDragEnd = {
                        accumulatedDrag = 0f
                        dragOffset = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDrag += dragAmount
                        dragOffset = accumulatedDrag.coerceIn(
                            -stepThreshold,
                            stepThreshold,
                        )
                        if (abs(accumulatedDrag) >= stepThreshold) {
                            val direction = if (accumulatedDrag < 0f) 1 else -1
                            accumulatedDrag = 0f
                            dragOffset = 0f
                            onExposureStep(direction)
                        }
                    },
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelSmall,
            )
            Column(
                horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.graphicsLayer {
                    translationY = dragOffset
                },
            ) {
                (-4..4).forEach { offset ->
                    val index = selectedIndex + offset
                    val label = exposures.getOrNull(index)?.let(value).orEmpty()
                    val distance = abs(offset)
                    val scale = when (distance) {
                        0 -> 1.20f
                        1 -> 1.00f
                        2 -> 0.90f
                        3 -> 0.80f
                        else -> 0.72f
                    }
                    val itemAlpha = when (distance) {
                        0 -> 1.00f
                        1 -> 0.86f
                        2 -> 0.68f
                        3 -> 0.50f
                        else -> 0.36f
                    }
                    Text(
                        text = label.ifEmpty { " " },
                        color = if (offset == 0) Color(0xFFE5B567) else Color.White,
                        modifier = Modifier
                            .height(22.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = itemAlpha
                            },
                        style = if (offset == 0) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExposurePanel(
    state: MeteringUiState,
    onIsoSelected: (Int) -> Unit,
    onFramePresetSelected: (FramePreset) -> Unit,
    onExposureCompensationChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.82f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ParameterValue(label = "ISO", value = state.selectedIso.toString())
                ParameterValue(
                    label = "EV",
                    value = state.ev100Metered?.let { "%.1f".format(it) } ?: "--",
                )
                ParameterValue(
                    label = "EC",
                    value = "%+.1f".format(state.exposureCompensation),
                )
                ParameterValue(
                    label = "推荐",
                    value = state.primaryExposure?.let {
                        "${it.apertureLabel}  ${it.shutterLabel}"
                    } ?: "--",
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            ControlLabel(text = "ISO")
            OptionRow {
                MeteringViewModel.isoOptions.forEach { iso ->
                    ChoiceButton(
                        text = iso.toString(),
                        selected = state.selectedIso == iso,
                        onClick = { onIsoSelected(iso) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            ControlLabel(text = "曝光补偿")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onExposureCompensationChanged(-1.0 / 3.0) },
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Text("-1/3")
                }
                Text(
                    text = "%+.1f EV".format(state.exposureCompensation),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    onClick = { onExposureCompensationChanged(1.0 / 3.0) },
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Text("+1/3")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            ControlLabel(text = "取景范围")
            OptionRow {
                FramePreset.entries.forEach { preset ->
                    ChoiceButton(
                        text = preset.displayName,
                        selected = state.framePreset == preset,
                        onClick = { onFramePresetSelected(preset) },
                    )
                }
            }

            state.errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.62f),
        style = MaterialTheme.typography.labelSmall,
    )
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun OptionRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color.White else Color.White.copy(alpha = 0.14f),
            contentColor = if (selected) Color.Black else Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = text)
    }
    Spacer(modifier = Modifier.width(0.dp))
}

@Composable
private fun ParameterValue(
    label: String,
    value: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun PermissionContent(
    buttonText: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        ) {}
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.camera_permission_title),
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.camera_permission_message),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onClick) {
            Text(buttonText)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
