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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightmeter.app.R
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.camera.CameraZoomState
import com.lightmeter.app.camera.ViewfinderProjection
import com.lightmeter.app.camera.ViewfinderProjectionCalculator
import com.lightmeter.app.exposure.ExposurePair
import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.filmpreview.FilmExposureSimulator
import com.lightmeter.app.metering.CameraMeteringPreset
import com.lightmeter.app.metering.ExposureRiskCalculator
import com.lightmeter.app.metering.ExposureRiskMask
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.FilmLatitudePreset
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.NormalizedMeteringRect
import com.lightmeter.app.metering.NormalizedPoint
import com.lightmeter.app.metering.MeteringResult
import com.lightmeter.app.settings.SharedPreferencesAppSettingsStore
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.activation.DebugToolsDialog
import com.lightmeter.app.ui.theme.AppThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val DEFAULT_CAMERA_OPTICS = CameraOptics(
    sensorWidthMm = 36.0,
    sensorHeightMm = 24.0,
    focalLengthMm = 24.0,
)

private val PreviewAccent = Color(0xFFD3AA5F)
private const val ZOOM_RATIO_TOLERANCE = 0.02f
private const val VIEWFINDER_CHROME_SCALE = 0.75f

@Composable
fun MeteringRoute(
    calibrationOffset: Double = 0.0,
    onExit: () -> Unit = {},
    themeStyle: AppThemeStyle = AppThemeStyle.DARK,
    onThemeStyleChanged: (AppThemeStyle) -> Unit = {},
    onPreviewChromeVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val settingsStore = remember(context) {
        SharedPreferencesAppSettingsStore(context.applicationContext)
    }
    val viewModel: MeteringViewModel = viewModel {
        MeteringViewModel(settingsStore)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = remember(context) { context.findActivity() }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(calibrationOffset) {
        viewModel.applyCalibrationOffset(calibrationOffset)
    }

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
        onRestoreMeteringPreset = viewModel::restoreMeteringPreset,
        onMeteringPresetSelected = viewModel::selectMeteringPreset,
        onCameraMeteringPresetSelected = viewModel::selectCameraMeteringPreset,
        onSpotAreaChanged = viewModel::adjustSpotAreaPercent,
        onCenterAreaChanged = viewModel::adjustCenterAreaPercent,
        onCenterWeightChanged = viewModel::adjustCenterWeightPercent,
        onExposureRiskEnabledChanged = viewModel::setExposureRiskEnabled,
        onFilmLatitudePresetSelected = viewModel::selectFilmLatitudePreset,
        onHighlightLatitudeChanged = viewModel::adjustHighlightLatitude,
        onShadowLatitudeChanged = viewModel::adjustShadowLatitude,
        onSaveSettings = viewModel::saveSettings,
        onIsoSelected = viewModel::selectIso,
        onFrameFormatSelected = viewModel::selectFrameFormat,
        onFocalLengthChanged = viewModel::selectFocalLength,
        onExposureCompensationSelected = viewModel::selectExposureCompensation,
        onApertureStep = viewModel::stepAperture,
        onShutterStep = viewModel::stepShutter,
        onFreezePreview = viewModel::freezePreview,
        onFrozenSnapshot = viewModel::onFrozenSnapshot,
        onResumeLive = viewModel::resumeLivePreview,
        onFreezeCaptureFailed = viewModel::onFreezeCaptureFailed,
        onExit = onExit,
        themeStyle = themeStyle,
        onThemeStyleChanged = onThemeStyleChanged,
        onPreviewChromeVisibilityChanged = onPreviewChromeVisibilityChanged,
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
    onRestoreMeteringPreset: () -> Unit,
    onMeteringPresetSelected: (MeteringMode) -> Unit,
    onCameraMeteringPresetSelected: (CameraMeteringPreset?) -> Unit,
    onSpotAreaChanged: (Int) -> Unit,
    onCenterAreaChanged: (Int) -> Unit,
    onCenterWeightChanged: (Int) -> Unit,
    onExposureRiskEnabledChanged: (Boolean) -> Unit,
    onFilmLatitudePresetSelected: (FilmLatitudePreset?) -> Unit,
    onHighlightLatitudeChanged: (Double) -> Unit,
    onShadowLatitudeChanged: (Double) -> Unit,
    onSaveSettings: () -> Boolean,
    onIsoSelected: (Int) -> Unit,
    onFrameFormatSelected: (FrameFormat) -> Unit,
    onFocalLengthChanged: (Double) -> Unit,
    onExposureCompensationSelected: (Double) -> Unit,
    onApertureStep: (Int) -> Unit,
    onShutterStep: (Int) -> Unit,
    onFreezePreview: () -> Unit,
    onFrozenSnapshot: (Int, ExposureSnapshot) -> Unit,
    onResumeLive: () -> Unit,
    onFreezeCaptureFailed: () -> Unit,
    onExit: () -> Unit,
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onPreviewChromeVisibilityChanged: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (state.permissionState) {
            CameraPermissionState.GRANTED -> CameraContent(
                state = state,
                onCameraReady = onCameraReady,
                onCameraOpticsAvailable = onCameraOpticsAvailable,
                onCameraError = onCameraError,
                onMeteringResult = onMeteringResult,
                onSpotSelected = onSpotSelected,
                onRestoreMeteringPreset = onRestoreMeteringPreset,
                onMeteringPresetSelected = onMeteringPresetSelected,
                onCameraMeteringPresetSelected = onCameraMeteringPresetSelected,
                onSpotAreaChanged = onSpotAreaChanged,
                onCenterAreaChanged = onCenterAreaChanged,
                onCenterWeightChanged = onCenterWeightChanged,
                onExposureRiskEnabledChanged = onExposureRiskEnabledChanged,
                onFilmLatitudePresetSelected = onFilmLatitudePresetSelected,
                onHighlightLatitudeChanged = onHighlightLatitudeChanged,
                onShadowLatitudeChanged = onShadowLatitudeChanged,
                onSaveSettings = onSaveSettings,
                onIsoSelected = onIsoSelected,
                onFrameFormatSelected = onFrameFormatSelected,
                onFocalLengthChanged = onFocalLengthChanged,
                onExposureCompensationSelected = onExposureCompensationSelected,
                onApertureStep = onApertureStep,
                onShutterStep = onShutterStep,
                onFreezePreview = onFreezePreview,
                onFrozenSnapshot = onFrozenSnapshot,
                onResumeLive = onResumeLive,
                onFreezeCaptureFailed = onFreezeCaptureFailed,
                onExit = onExit,
                themeStyle = themeStyle,
                onThemeStyleChanged = onThemeStyleChanged,
                onPreviewChromeVisibilityChanged = onPreviewChromeVisibilityChanged,
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
    onRestoreMeteringPreset: () -> Unit,
    onMeteringPresetSelected: (MeteringMode) -> Unit,
    onCameraMeteringPresetSelected: (CameraMeteringPreset?) -> Unit,
    onSpotAreaChanged: (Int) -> Unit,
    onCenterAreaChanged: (Int) -> Unit,
    onCenterWeightChanged: (Int) -> Unit,
    onExposureRiskEnabledChanged: (Boolean) -> Unit,
    onFilmLatitudePresetSelected: (FilmLatitudePreset?) -> Unit,
    onHighlightLatitudeChanged: (Double) -> Unit,
    onShadowLatitudeChanged: (Double) -> Unit,
    onSaveSettings: () -> Boolean,
    onIsoSelected: (Int) -> Unit,
    onFrameFormatSelected: (FrameFormat) -> Unit,
    onFocalLengthChanged: (Double) -> Unit,
    onExposureCompensationSelected: (Double) -> Unit,
    onApertureStep: (Int) -> Unit,
    onShutterStep: (Int) -> Unit,
    onFreezePreview: () -> Unit,
    onFrozenSnapshot: (Int, ExposureSnapshot) -> Unit,
    onResumeLive: () -> Unit,
    onFreezeCaptureFailed: () -> Unit,
    onExit: () -> Unit,
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onPreviewChromeVisibilityChanged: (Boolean) -> Unit,
) {
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var simulatedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isExposureSimulationEnabled by rememberSaveable { mutableStateOf(false) }
    var frozenExposureSnapshot by remember { mutableStateOf<ExposureSnapshot?>(null) }
    var exposureRiskMask by remember { mutableStateOf<ExposureRiskMask?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraZoomState by remember { mutableStateOf(CameraZoomState()) }
    var frozenChromeVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val selectedFrameAspectRatio = remember(state.frameFormat) {
        min(state.frameFormat.frameWidthMm, state.frameFormat.frameHeightMm) /
            max(state.frameFormat.frameWidthMm, state.frameFormat.frameHeightMm)
    }
    val hardwareFocalRangeKnown = state.cameraOptics != null &&
        cameraZoomState.isInitialized
    val supportedFocalRange = remember(
        state.frameFormat,
        state.cameraOptics,
        cameraZoomState.isInitialized,
        cameraZoomState.minZoomRatio,
        cameraZoomState.maxZoomRatio,
    ) {
        val optics = state.cameraOptics
        if (
            optics == null ||
            !cameraZoomState.isInitialized
        ) {
            null
        } else {
            ViewfinderProjectionCalculator.supportedFocalLengthRange(
                previewAspectRatio = selectedFrameAspectRatio,
                frameFormat = state.frameFormat,
                cameraOptics = optics,
                minimumZoomRatio = cameraZoomState.minZoomRatio.toDouble(),
                maximumZoomRatio = cameraZoomState.maxZoomRatio.toDouble(),
                allowedMinimumFocalLengthMm = MeteringViewModel.MIN_FOCAL_LENGTH_MM,
                allowedMaximumFocalLengthMm = MeteringViewModel.MAX_FOCAL_LENGTH_MM,
            )
        }
    }
    val focalLengthRange = when {
        !hardwareFocalRangeKnown ->
            MeteringViewModel.MIN_FOCAL_LENGTH_MM..
                MeteringViewModel.MAX_FOCAL_LENGTH_MM
        supportedFocalRange != null -> supportedFocalRange
        else ->
            MeteringViewModel.MIN_FOCAL_LENGTH_MM..
                MeteringViewModel.MIN_FOCAL_LENGTH_MM
    }
    LaunchedEffect(
        state.frameFormat,
        focalLengthRange.start,
        focalLengthRange.endInclusive,
        hardwareFocalRangeKnown,
    ) {
        if (hardwareFocalRangeKnown) {
            val supportedFocalLength = state.focalLengthMm.coerceIn(
                focalLengthRange.start,
                focalLengthRange.endInclusive,
            )
            if (supportedFocalLength != state.focalLengthMm) {
                onFocalLengthChanged(supportedFocalLength)
            }
        }
    }
    val projection = remember(
        previewSize,
        state.frameFormat,
        state.focalLengthMm,
        state.cameraOptics,
    ) {
        if (previewSize.width == 0 || previewSize.height == 0) {
            ViewfinderProjection(1.0, 1.0)
        } else {
            ViewfinderProjectionCalculator.calculate(
                previewAspectRatio = previewSize.width / previewSize.height.toDouble(),
                frameFormat = state.frameFormat,
                targetFocalLengthMm = state.focalLengthMm,
                cameraOptics = state.cameraOptics ?: DEFAULT_CAMERA_OPTICS,
            )
        }
    }
    val targetZoomRatio = projection.fitZoomRatio.toFloat()
    val effectiveZoomRatio = targetZoomRatio.coerceIn(
        cameraZoomState.minZoomRatio,
        cameraZoomState.maxZoomRatio,
    )
    val isZoomReady = cameraZoomState.isInitialized &&
        abs(cameraZoomState.zoomRatio - effectiveZoomRatio) <= ZOOM_RATIO_TOLERANCE
    val normalizedViewfinder = remember(projection, effectiveZoomRatio) {
        projection.viewfinderAt(effectiveZoomRatio.toDouble())
    }
    LaunchedEffect(
        frozenExposureSnapshot,
        normalizedViewfinder,
        state.exposureCompensation,
        state.exposureRiskEnabled,
        state.highlightLatitudeStops,
        state.shadowLatitudeStops,
    ) {
        val snapshot = frozenExposureSnapshot
        exposureRiskMask = if (
            !state.exposureRiskEnabled ||
            snapshot == null
        ) {
            null
        } else {
            val referenceEv100 = ExposureRiskCalculator.referenceEv100(
                frozenMeteredEv100 = snapshot.meteredEv100,
                exposureCompensation = state.exposureCompensation,
            )
            withContext(Dispatchers.Default) {
                ExposureRiskCalculator.calculate(
                    exposureMap = snapshot.exposureMap,
                    viewfinder = normalizedViewfinder,
                    referenceEv100 = referenceEv100,
                    highlightLatitudeStops = state.highlightLatitudeStops,
                    shadowLatitudeStops = state.shadowLatitudeStops,
                )
            }
        }
    }
    val exposureRiskBitmap = remember(exposureRiskMask) {
        exposureRiskMask?.let {
            Bitmap.createBitmap(
                it.argb,
                it.width,
                it.height,
                Bitmap.Config.ARGB_8888,
            )
        }
    }
    LaunchedEffect(
        frozenFrame,
        state.exposureCompensation,
    ) {
        val source = frozenFrame
        simulatedFrame = if (source == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    FilmExposureSimulator.renderExposureCompensation(
                        source = source,
                        exposureCompensation = state.exposureCompensation,
                    )
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(state.isFrozen) {
        if (!state.isFrozen) {
            isExposureSimulationEnabled = false
            frozenFrame = null
            simulatedFrame = null
            frozenExposureSnapshot = null
            exposureRiskMask = null
        }
    }
    LaunchedEffect(state.isFrozen, state.freezeRequestId) {
        frozenChromeVisible = true
        isExposureSimulationEnabled = false
    }
    LaunchedEffect(state.isFrozen, frozenChromeVisible) {
        onPreviewChromeVisibilityChanged(!state.isFrozen || frozenChromeVisible)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val frameAspectRatio = selectedFrameAspectRatio.toFloat()
            val availableAspectRatio = if (maxHeight.value > 0f) {
                maxWidth.value / maxHeight.value
            } else {
                frameAspectRatio
            }
            val frameModifier = if (availableAspectRatio > frameAspectRatio) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(frameAspectRatio)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameAspectRatio)
            }
            val frameShape = RoundedCornerShape(8.dp)

            Box(
                modifier = frameModifier
                    .background(Color.Black, frameShape)
                    .clip(frameShape)
                    .border(1.dp, PreviewAccent.copy(alpha = 0.72f), frameShape)
                    .onSizeChanged { previewSize = it },
            ) {
                key(state.frameFormat) {
                    CameraPreviewView(
                        meteringConfig = MeteringConfig(
                            mode = state.meteringMode,
                            spotPoint = state.spotMeteringPoint,
                            spotAreaPercent = state.spotAreaPercent,
                            centerAreaPercent = state.centerAreaPercent,
                            centerWeightPercent = state.centerWeightPercent,
                            viewfinderRect = normalizedViewfinder,
                            previewAspectRatio = if (previewSize.height > 0) {
                                previewSize.width / previewSize.height.toDouble()
                            } else {
                                1.0
                            },
                            targetZoomRatio = effectiveZoomRatio.toDouble(),
                            isZoomReady = isZoomReady,
                            revision = state.meteringRevision,
                            calibrationOffset = state.calibrationOffset,
                        ),
                        targetZoomRatio = if (state.isFrozen) {
                            cameraZoomState.zoomRatio
                        } else {
                            targetZoomRatio
                        },
                        freezeRequestId = state.freezeRequestId,
                        shouldCaptureFrame = state.isFrozen,
                        onMeteringResult = onMeteringResult,
                        onFrameCaptured = { capturedFrame ->
                            val requestId = capturedFrame?.requestId
                            val bitmap = capturedFrame?.bitmap
                            val snapshot = capturedFrame?.snapshot
                            if (
                                requestId == null ||
                                requestId != state.freezeRequestId ||
                                bitmap == null ||
                                snapshot == null ||
                                snapshot.revision != state.meteringRevision
                            ) {
                                onFreezeCaptureFailed()
                            } else {
                                onFrozenSnapshot(requestId, snapshot)
                                frozenFrame = bitmap
                                simulatedFrame = null
                                frozenExposureSnapshot = snapshot
                            }
                        },
                        onOpticsAvailable = onCameraOpticsAvailable,
                        onZoomStateChanged = { cameraZoomState = it },
                        onReady = onCameraReady,
                        onError = onCameraError,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (state.isFrozen) {
                    val displayedFrame = if (isExposureSimulationEnabled) {
                        simulatedFrame ?: frozenFrame
                    } else {
                        frozenFrame
                    }
                    displayedFrame?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = if (isExposureSimulationEnabled) {
                                "专业模式曝光结果模拟"
                            } else {
                                "定格测光画面"
                            },
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (!isExposureSimulationEnabled) {
                        exposureRiskBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "曝光风险预览",
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                CameraViewfinderMask(
                    viewfinder = normalizedViewfinder,
                    modifier = Modifier.fillMaxSize(),
                )

                if (!state.isFrozen || frozenChromeVisible) {
                    ViewfinderOverlay(
                        state = state,
                        viewfinder = normalizedViewfinder,
                        enabled = !state.isFrozen,
                        onSpotSelected = onSpotSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (state.isFrozen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.freezeRequestId) {
                                detectTapGestures {
                                    frozenChromeVisible = !frozenChromeVisible
                                }
                            },
                    )
                }

                if (state.isFrozen && !isExposureSimulationEnabled) {
                    exposureRiskMask?.let { riskMask ->
                        ExposureRiskLegend(
                            riskMask = riskMask,
                            exposureCompensation = state.exposureCompensation,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp),
                        )
                    }
                }

                if (!state.isFrozen || frozenChromeVisible) {
                    PreviewTopControls(
                        onExit = onExit,
                        onOpenSettings = { showSettings = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(14.dp),
                    )

                    ExposureScaleOverlay(
                        apertureCandidates = state.apertureCandidates,
                        shutterCandidates = state.shutterCandidates,
                        primaryExposure = state.primaryExposure,
                        onApertureStep = onApertureStep,
                        onShutterStep = onShutterStep,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 56.dp),
                    )

                    CaptureControls(
                        isFrozen = state.isFrozen,
                        freezeVisualAvailable = state.isCameraReady &&
                            state.ev100Metered != null,
                        canFreeze = state.isCameraReady &&
                            state.ev100Metered != null &&
                            isZoomReady,
                        meteringMode = state.meteringMode,
                        meteringPreset = state.meteringPreset,
                        hasSpotMeteringPoint = state.spotMeteringPoint != null,
                        exposureSimulationAvailable = state.isFrozen &&
                            exposureRiskMask != null &&
                            simulatedFrame != null,
                        isExposureSimulationEnabled = isExposureSimulationEnabled,
                        onFreezePreview = onFreezePreview,
                        onResumeLive = onResumeLive,
                        onRestoreMeteringPreset = onRestoreMeteringPreset,
                        onToggleExposureSimulation = {
                            isExposureSimulationEnabled = !isExposureSimulationEnabled
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    )
                }
            }
        }

        ExposurePanel(
            state = state,
            onIsoSelected = onIsoSelected,
            onFrameFormatSelected = onFrameFormatSelected,
            onMeteringPresetSelected = onMeteringPresetSelected,
            onCameraMeteringPresetSelected = onCameraMeteringPresetSelected,
            onSpotAreaChanged = onSpotAreaChanged,
            onCenterAreaChanged = onCenterAreaChanged,
            onCenterWeightChanged = onCenterWeightChanged,
            onExposureRiskEnabledChanged = onExposureRiskEnabledChanged,
            onFilmLatitudePresetSelected = onFilmLatitudePresetSelected,
            onHighlightLatitudeChanged = onHighlightLatitudeChanged,
            onShadowLatitudeChanged = onShadowLatitudeChanged,
            onSaveSettings = onSaveSettings,
            showSettings = showSettings,
            onShowSettingsChanged = { showSettings = it },
            themeStyle = themeStyle,
            onThemeStyleChanged = onThemeStyleChanged,
            onExposureCompensationSelected = onExposureCompensationSelected,
            zoomRatio = cameraZoomState.zoomRatio,
            zoomLimited = targetZoomRatio < cameraZoomState.minZoomRatio - 0.01f ||
                targetZoomRatio > cameraZoomState.maxZoomRatio + 0.01f,
            minimumFocalLengthMm = focalLengthRange.start,
            maximumFocalLengthMm = focalLengthRange.endInclusive,
            hasSupportedFocalRange = !hardwareFocalRangeKnown ||
                supportedFocalRange != null,
            onFocalLengthChanged = onFocalLengthChanged,
            modifier = Modifier
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun ViewfinderOverlay(
    state: MeteringUiState,
    viewfinder: NormalizedMeteringRect,
    enabled: Boolean,
    onSpotSelected: (NormalizedPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionModifier = if (enabled) {
        Modifier.pointerInput(viewfinder) {
            detectTapGestures { offset ->
                if (size.width == 0 || size.height == 0) return@detectTapGestures
                val frame = viewfinder.toComposeRect(
                    size.width.toFloat(),
                    size.height.toFloat(),
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
        val frame = viewfinder.toComposeRect(size.width, size.height)
        when (state.meteringMode) {
            MeteringMode.SPOT -> {
                val point = state.spotMeteringPoint
                val center = if (point == null) {
                    frame.center
                } else {
                    Offset(
                        x = size.width * point.x.toFloat(),
                        y = size.height * point.y.toFloat(),
                    )
                }
                drawCircle(
                    color = Color.White,
                    radius = meteringRadius(
                        width = frame.width,
                        height = frame.height,
                        areaPercent = state.spotAreaPercent,
                    ) * 0.25f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            MeteringMode.CENTER_WEIGHTED,
            MeteringMode.AVERAGE -> Unit
        }
    }
}

private fun meteringRadius(
    width: Float,
    height: Float,
    areaPercent: Int,
): Float {
    return sqrt(width * height * areaPercent / 100f / PI.toFloat())
}

private fun NormalizedMeteringRect.toComposeRect(
    viewWidth: Float,
    viewHeight: Float,
): Rect {
    return Rect(
        left = (left * viewWidth).toFloat(),
        top = (top * viewHeight).toFloat(),
        right = (right * viewWidth).toFloat(),
        bottom = (bottom * viewHeight).toFloat(),
    )
}

@Composable
private fun PreviewTopControls(
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chromeScale = VIEWFINDER_CHROME_SCALE
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.clickable(onClick = onExit),
            color = Color.Black.copy(alpha = 0.68f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = (14 * chromeScale).dp,
                    vertical = (10 * chromeScale).dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = null,
                    tint = PreviewAccent,
                    modifier = Modifier.size((22 * chromeScale).dp),
                )
                Text(
                    text = "模式",
                    color = PreviewAccent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Surface(
            modifier = Modifier
                .size((48 * chromeScale).dp)
                .clickable(onClick = onOpenSettings),
            color = Color.Black.copy(alpha = 0.68f),
            shape = CircleShape,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = PreviewAccent,
                    modifier = Modifier.size((24 * chromeScale).dp),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FocalLengthSlider(
    frameFormat: FrameFormat,
    focalLengthMm: Double,
    zoomRatio: Float,
    zoomLimited: Boolean,
    minimumFocalLengthMm: Double,
    maximumFocalLengthMm: Double,
    hasSupportedFocalRange: Boolean,
    enabled: Boolean,
    onFocalLengthChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = buildString {
                append(frameFormat.displayName)
                append(" · ")
                append(focalLengthMm.roundToInt())
                append("mm · ")
                append("%.1f×".format(zoomRatio))
                if (zoomLimited) append(" 上限")
                if (!hasSupportedFocalRange) append(" · 无可模拟焦段")
            },
            color = if (zoomLimited) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (maximumFocalLengthMm > minimumFocalLengthMm) {
            Slider(
                value = focalLengthMm.coerceIn(
                    minimumFocalLengthMm,
                    maximumFocalLengthMm,
                ).toFloat(),
                onValueChange = {
                    onFocalLengthChanged(it.roundToInt().toDouble())
                },
                valueRange = minimumFocalLengthMm.toFloat()..
                    maximumFocalLengthMm.toFloat(),
                steps = (maximumFocalLengthMm - minimumFocalLengthMm)
                    .roundToInt()
                    .minus(1)
                    .coerceAtLeast(0),
                enabled = enabled && hasSupportedFocalRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                thumb = {
                    Surface(
                        color = if (enabled && hasSupportedFocalRange) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
                        },
                        shape = RoundedCornerShape(3.dp),
                        modifier = Modifier.size(width = 12.dp, height = 20.dp),
                    ) {}
                },
                track = {
                    val fraction = (
                        (focalLengthMm - minimumFocalLengthMm) /
                            (maximumFocalLengthMm - minimumFocalLengthMm)
                        ).toFloat().coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(
                                    alpha = if (enabled) 1f else 0.55f,
                                ),
                                RoundedCornerShape(1.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .background(
                                    if (enabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
                                    },
                                    RoundedCornerShape(1.dp),
                                ),
                        )
                    }
                },
            )
        } else {
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ExposureRiskLegend(
    riskMask: ExposureRiskMask,
    exposureCompensation: Double,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.68f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            RiskLegendItem(
                color = Color(0xFFFF2D2D),
                label = "高光 %.1f%%".format(riskMask.highlightRatio * 100.0),
            )
            RiskLegendItem(
                color = Color(0xFF00D26A),
                label = "暗部 %.1f%%".format(riskMask.shadowRatio * 100.0),
            )
            Text(
                text = "EC ${formatExposureCompensation(exposureCompensation)}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RiskLegendItem(
    color: Color,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CaptureControls(
    isFrozen: Boolean,
    freezeVisualAvailable: Boolean,
    canFreeze: Boolean,
    meteringMode: MeteringMode,
    meteringPreset: MeteringMode,
    hasSpotMeteringPoint: Boolean,
    exposureSimulationAvailable: Boolean,
    isExposureSimulationEnabled: Boolean,
    onFreezePreview: () -> Unit,
    onResumeLive: () -> Unit,
    onRestoreMeteringPreset: () -> Unit,
    onToggleExposureSimulation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (
            !isFrozen &&
            (meteringMode != meteringPreset || hasSpotMeteringPoint)
        ) {
            SymbolButton(
                symbol = "◎",
                onClick = onRestoreMeteringPreset,
                enabled = true,
            )
        }
        SymbolButton(
            symbol = if (isFrozen) "▶" else "⏸",
            onClick = if (isFrozen) onResumeLive else onFreezePreview,
            enabled = isFrozen || canFreeze,
            visuallyEnabled = isFrozen || freezeVisualAvailable,
        )
        if (exposureSimulationAvailable) {
            ExposureSimulationButton(
                isSimulationEnabled = isExposureSimulationEnabled,
                onClick = onToggleExposureSimulation,
            )
        }
    }
}

@Composable
private fun ExposureSimulationButton(
    isSimulationEnabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSimulationEnabled) PreviewAccent else Color.White,
            contentColor = Color.Black,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(52.dp),
    ) {
        Icon(
            imageVector = if (isSimulationEnabled) {
                Icons.Outlined.VisibilityOff
            } else {
                Icons.Outlined.Visibility
            },
            contentDescription = if (isSimulationEnabled) {
                "关闭曝光模拟"
            } else {
                "开启曝光模拟"
            },
            modifier = Modifier.size(25.dp),
        )
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    onClick: () -> Unit,
    enabled: Boolean,
    visuallyEnabled: Boolean = enabled,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
            disabledContainerColor = if (visuallyEnabled) {
                Color.White
            } else {
                Color.White.copy(alpha = 0.35f)
            },
            disabledContentColor = if (visuallyEnabled) {
                Color.Black
            } else {
                Color.Black.copy(alpha = 0.5f)
            },
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
    apertureCandidates: List<String>,
    shutterCandidates: List<String>,
    primaryExposure: ExposurePair?,
    onApertureStep: (Int) -> Unit,
    onShutterStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedExposure = primaryExposure ?: return
    val selectedApertureIndex = apertureCandidates.indexOf(selectedExposure.apertureLabel)
        .takeIf { it >= 0 } ?: return
    val selectedShutterIndex = shutterCandidates.indexOf(selectedExposure.shutterLabel)
        .takeIf { it >= 0 } ?: return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposureSideScale(
            title = "光圈",
            candidates = apertureCandidates,
            selectedIndex = selectedApertureIndex,
            onStep = onApertureStep,
        )
        ExposureSideScale(
            title = "快门",
            candidates = shutterCandidates,
            selectedIndex = selectedShutterIndex,
            onStep = onShutterStep,
        )
    }
}

@Composable
private fun ExposureSideScale(
    title: String,
    candidates: List<String>,
    selectedIndex: Int,
    onStep: (Int) -> Unit,
) {
    var dragOffset by remember { mutableStateOf(0f) }
    val chromeScale = VIEWFINDER_CHROME_SCALE

    Surface(
        color = Color.Black.copy(alpha = 0.54f),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier
            .width((68 * chromeScale).dp)
            .pointerInput(candidates, selectedIndex) {
                var accumulatedDrag = 0f
                val stepThreshold = (28 * chromeScale).dp.toPx()
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
                            onStep(direction)
                        }
                    },
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 3.dp,
                vertical = (9 * chromeScale).dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.graphicsLayer {
                    translationY = dragOffset
                },
            ) {
                (-4..4).forEach { offset ->
                    val index = selectedIndex + offset
                    val label = candidates.getOrNull(index).orEmpty()
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
                        color = if (offset == 0) PreviewAccent else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((22 * chromeScale).dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                alpha = itemAlpha
                            },
                        style = if (offset == 0) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private enum class QuickSetting {
    ISO,
    EXPOSURE_COMPENSATION,
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun QuickSettingButton(
    label: String,
    value: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = onOpen,
            onLongClickLabel = "设置$label",
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun QuickSettingDialog(
    setting: QuickSetting,
    selectedIso: Int,
    selectedExposureCompensation: Double,
    onIsoSelected: (Int) -> Unit,
    onExposureCompensationSelected: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val isoOptions = MeteringViewModel.isoOptions
    val exposureCompensationOptions = MeteringViewModel.exposureCompensationOptions
    var pendingIso by remember(setting, selectedIso) {
        mutableStateOf(
            isoOptions.minByOrNull { abs(it - selectedIso) }
                ?: isoOptions.first(),
        )
    }
    var pendingExposureCompensation by remember(
        setting,
        selectedExposureCompensation,
    ) {
        mutableStateOf(selectedExposureCompensation)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(260.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(
                if (setting == QuickSetting.ISO) {
                    "选择 ISO"
                } else {
                    "选择曝光补偿"
                },
            )
        },
        text = {
            if (setting == QuickSetting.ISO) {
                val selectedIndex = isoOptions.indexOf(pendingIso)
                    .coerceAtLeast(0)
                VerticalValueWheel(
                    values = isoOptions.map(Int::toString),
                    selectedIndex = selectedIndex,
                    onSelectedIndexChanged = { pendingIso = isoOptions[it] },
                )
            } else {
                val selectedIndex = exposureCompensationOptions
                    .indices
                    .minByOrNull {
                        abs(
                            exposureCompensationOptions[it] -
                                pendingExposureCompensation,
                        )
                    } ?: 0
                VerticalValueWheel(
                    values = exposureCompensationOptions.map(
                        ::formatExposureCompensation,
                    ),
                    selectedIndex = selectedIndex,
                    onSelectedIndexChanged = {
                        pendingExposureCompensation =
                            exposureCompensationOptions[it]
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (setting == QuickSetting.ISO) {
                        onIsoSelected(pendingIso)
                    } else {
                        onExposureCompensationSelected(
                            pendingExposureCompensation,
                        )
                    }
                    onDismiss()
                },
            ) {
                Text(
                    text = "确定",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun VerticalValueWheel(
    values: List<String>,
    selectedIndex: Int,
    onSelectedIndexChanged: (Int) -> Unit,
) {
    val currentOnSelectedIndexChanged by rememberUpdatedState(
        onSelectedIndexChanged,
    )
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    var dragOffset by remember { mutableStateOf(0f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(values) {
                var accumulatedDrag = 0f
                var gestureIndex = currentSelectedIndex
                val stepThreshold = 46.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulatedDrag = 0f
                        gestureIndex = currentSelectedIndex
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
                        while (abs(accumulatedDrag) >= stepThreshold) {
                            val step = if (accumulatedDrag < 0f) 1 else -1
                            val nextIndex = (gestureIndex + step)
                                .coerceIn(values.indices)
                            if (nextIndex == gestureIndex) {
                                accumulatedDrag = 0f
                            } else {
                                gestureIndex = nextIndex
                                currentOnSelectedIndexChanged(nextIndex)
                                accumulatedDrag += if (step > 0) {
                                    stepThreshold
                                } else {
                                    -stepThreshold
                                }
                            }
                        }
                        dragOffset = accumulatedDrag.coerceIn(
                            -stepThreshold,
                            stepThreshold,
                        )
                    },
                )
            },
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .graphicsLayer {
                    translationY = dragOffset
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            (-2..2).forEach { offset ->
                val value = values.getOrNull(selectedIndex + offset).orEmpty()
                val selected = offset == 0
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                ) {
                    Text(
                        text = value.ifEmpty { " " },
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (abs(offset) == 1) 0.68f else 0.38f,
                            )
                        },
                        style = if (selected) {
                            MaterialTheme.typography.headlineMedium
                        } else if (abs(offset) == 1) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        modifier = Modifier.graphicsLayer {
                            scaleX = if (selected) 1f else 0.86f
                            scaleY = if (selected) 1f else 0.86f
                        },
                    )
                }
            }
        }
    }
}

private fun formatExposureCompensation(value: Double): String {
    return "%+.1f EV".format(value)
}

@Composable
private fun ExposurePanel(
    state: MeteringUiState,
    onIsoSelected: (Int) -> Unit,
    onFrameFormatSelected: (FrameFormat) -> Unit,
    onMeteringPresetSelected: (MeteringMode) -> Unit,
    onCameraMeteringPresetSelected: (CameraMeteringPreset?) -> Unit,
    onSpotAreaChanged: (Int) -> Unit,
    onCenterAreaChanged: (Int) -> Unit,
    onCenterWeightChanged: (Int) -> Unit,
    onExposureRiskEnabledChanged: (Boolean) -> Unit,
    onFilmLatitudePresetSelected: (FilmLatitudePreset?) -> Unit,
    onHighlightLatitudeChanged: (Double) -> Unit,
    onShadowLatitudeChanged: (Double) -> Unit,
    onSaveSettings: () -> Boolean,
    showSettings: Boolean,
    onShowSettingsChanged: (Boolean) -> Unit,
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onExposureCompensationSelected: (Double) -> Unit,
    zoomRatio: Float,
    zoomLimited: Boolean,
    minimumFocalLengthMm: Double,
    maximumFocalLengthMm: Double,
    hasSupportedFocalRange: Boolean,
    onFocalLengthChanged: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var quickSetting by rememberSaveable { mutableStateOf<QuickSetting?>(null) }
    var showDebugTools by rememberSaveable { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ParameterValue(
                    label = "EV100",
                    value = state.ev100Metered?.let { "%.1f".format(it) } ?: "--",
                    horizontalAlignment = Alignment.Start,
                    modifier = Modifier.weight(1f),
                )
                ParameterValue(
                    label = "曝光组合",
                    value = state.primaryExposure?.let {
                        "${it.apertureLabel}  ${it.shutterLabel}"
                    } ?: "--",
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuickSettingButton(
                    label = "ISO",
                    value = state.selectedIso.toString(),
                    onOpen = { quickSetting = QuickSetting.ISO },
                    modifier = Modifier.weight(1f),
                )
                QuickSettingButton(
                    label = "曝光补偿",
                    value = formatExposureCompensation(state.exposureCompensation),
                    onOpen = { quickSetting = QuickSetting.EXPOSURE_COMPENSATION },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            FocalLengthSlider(
                frameFormat = state.frameFormat,
                focalLengthMm = state.focalLengthMm,
                zoomRatio = zoomRatio,
                zoomLimited = zoomLimited,
                minimumFocalLengthMm = minimumFocalLengthMm,
                maximumFocalLengthMm = maximumFocalLengthMm,
                hasSupportedFocalRange = hasSupportedFocalRange,
                enabled = !state.isFrozen,
                onFocalLengthChanged = onFocalLengthChanged,
                modifier = Modifier.fillMaxWidth(),
            )

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedButton(
                    onClick = { showDebugTools = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("激活码生成器")
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

    quickSetting?.let { setting ->
        QuickSettingDialog(
            setting = setting,
            selectedIso = state.selectedIso,
            selectedExposureCompensation = state.exposureCompensation,
            onIsoSelected = {
                onIsoSelected(it)
                quickSetting = null
            },
            onExposureCompensationSelected = {
                onExposureCompensationSelected(it)
                quickSetting = null
            },
            onDismiss = { quickSetting = null },
        )
    }

    if (showSettings) {
        AppSettingsDialog(
            selectedFrameFormat = state.frameFormat,
            selectedMeteringPreset = state.meteringPreset,
            selectedCameraMeteringPreset = state.cameraMeteringPreset,
            spotAreaPercent = state.spotAreaPercent,
            centerAreaPercent = state.centerAreaPercent,
            centerWeightPercent = state.centerWeightPercent,
            exposureRiskEnabled = state.exposureRiskEnabled,
            selectedFilmLatitudePreset = state.filmLatitudePreset,
            highlightLatitudeStops = state.highlightLatitudeStops,
            shadowLatitudeStops = state.shadowLatitudeStops,
            themeStyle = themeStyle,
            onFrameFormatSelected = onFrameFormatSelected,
            onMeteringPresetSelected = onMeteringPresetSelected,
            onCameraMeteringPresetSelected = onCameraMeteringPresetSelected,
            onSpotAreaChanged = onSpotAreaChanged,
            onCenterAreaChanged = onCenterAreaChanged,
            onCenterWeightChanged = onCenterWeightChanged,
            onExposureRiskEnabledChanged = onExposureRiskEnabledChanged,
            onFilmLatitudePresetSelected = onFilmLatitudePresetSelected,
            onHighlightLatitudeChanged = onHighlightLatitudeChanged,
            onShadowLatitudeChanged = onShadowLatitudeChanged,
            onThemeStyleChanged = onThemeStyleChanged,
            onSave = {
                if (onSaveSettings()) {
                    onShowSettingsChanged(false)
                }
            },
            onDismiss = { onShowSettingsChanged(false) },
        )
    }

    if (showDebugTools) {
        AlertDialog(
            onDismissRequest = { showDebugTools = false },
            title = { },
            text = {
                DebugToolsDialog(
                    onDismiss = { showDebugTools = false },
                )
            },
            confirmButton = { },
        )
    }
}

@Composable
private fun AppSettingsDialog(
    selectedFrameFormat: FrameFormat,
    selectedMeteringPreset: MeteringMode,
    selectedCameraMeteringPreset: CameraMeteringPreset?,
    spotAreaPercent: Int,
    centerAreaPercent: Int,
    centerWeightPercent: Int,
    exposureRiskEnabled: Boolean,
    selectedFilmLatitudePreset: FilmLatitudePreset?,
    highlightLatitudeStops: Double,
    shadowLatitudeStops: Double,
    themeStyle: AppThemeStyle,
    onFrameFormatSelected: (FrameFormat) -> Unit,
    onMeteringPresetSelected: (MeteringMode) -> Unit,
    onCameraMeteringPresetSelected: (CameraMeteringPreset?) -> Unit,
    onSpotAreaChanged: (Int) -> Unit,
    onCenterAreaChanged: (Int) -> Unit,
    onCenterWeightChanged: (Int) -> Unit,
    onExposureRiskEnabledChanged: (Boolean) -> Unit,
    onFilmLatitudePresetSelected: (FilmLatitudePreset?) -> Unit,
    onHighlightLatitudeChanged: (Double) -> Unit,
    onShadowLatitudeChanged: (Double) -> Unit,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var frameFormatExpanded by rememberSaveable { mutableStateOf(true) }
    var meteringExpanded by rememberSaveable { mutableStateOf(false) }
    var exposureRiskExpanded by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CollapsibleSettingsSection(
                    title = "外观",
                    expanded = appearanceExpanded,
                    onToggle = { appearanceExpanded = !appearanceExpanded },
                ) {
                    OptionRow {
                        AppThemeStyle.entries
                            .filter(AppThemeStyle::available)
                            .forEach { style ->
                                ChoiceButton(
                                    text = style.displayName,
                                    selected = themeStyle == style,
                                    onClick = { onThemeStyleChanged(style) },
                                )
                            }
                    }
                }

                CollapsibleSettingsSection(
                    title = "画幅选择",
                    expanded = frameFormatExpanded,
                    onToggle = { frameFormatExpanded = !frameFormatExpanded },
                ) {
                    OptionRow {
                        FrameFormat.entries.forEach { format ->
                            ChoiceButton(
                                text = format.displayName,
                                selected = selectedFrameFormat == format,
                                onClick = { onFrameFormatSelected(format) },
                            )
                        }
                    }
                }

                CollapsibleSettingsSection(
                    title = "测光预设",
                    expanded = meteringExpanded,
                    onToggle = { meteringExpanded = !meteringExpanded },
                ) {
                    ControlLabel(text = "测光模式")
                    OptionRow {
                        MeteringMode.entries.forEach { mode ->
                            ChoiceButton(
                                text = mode.displayName(),
                                selected = selectedMeteringPreset == mode,
                                onClick = { onMeteringPresetSelected(mode) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    ControlLabel(text = "机型预设")
                    OptionRow {
                        ChoiceButton(
                            text = "自定义",
                            selected = selectedCameraMeteringPreset == null,
                            onClick = { onCameraMeteringPresetSelected(null) },
                        )
                        CameraMeteringPreset.entries.forEach { preset ->
                            ChoiceButton(
                                text = preset.displayName,
                                selected = selectedCameraMeteringPreset == preset,
                                onClick = { onCameraMeteringPresetSelected(preset) },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    when (selectedMeteringPreset) {
                        MeteringMode.SPOT -> {
                            PercentageControl(
                                label = "点测光面积",
                                value = spotAreaPercent,
                                onDecrease = { onSpotAreaChanged(-1) },
                                onIncrease = { onSpotAreaChanged(1) },
                            )
                            SettingHint("读取画面中心区域；点击画面后，测光中心移动到点击位置。")
                        }

                        MeteringMode.CENTER_WEIGHTED -> {
                            PercentageControl(
                                label = "中央区域面积",
                                value = centerAreaPercent,
                                onDecrease = { onCenterAreaChanged(-5) },
                                onIncrease = { onCenterAreaChanged(5) },
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            PercentageControl(
                                label = "中央测光权重",
                                value = centerWeightPercent,
                                onDecrease = { onCenterWeightChanged(-5) },
                                onIncrease = { onCenterWeightChanged(5) },
                            )
                            SettingHint(
                                "外围区域自动使用剩余 ${100 - centerAreaPercent}% 面积和 " +
                                    "${100 - centerWeightPercent}% 权重。",
                            )
                        }

                        MeteringMode.AVERAGE -> {
                            SettingHint("读取整个画面的平均亮度，不使用额外权重。")
                        }
                    }
                }

                CollapsibleSettingsSection(
                    title = "曝光风险宽容度",
                    expanded = exposureRiskExpanded,
                    onToggle = { exposureRiskExpanded = !exposureRiskExpanded },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "开启曝光风险预览",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (exposureRiskEnabled) "已开启" else "已关闭",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Switch(
                            checked = exposureRiskEnabled,
                            onCheckedChange = onExposureRiskEnabledChanged,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ControlLabel(text = "胶片预设")
                    OptionRow {
                        ChoiceButton(
                            text = "自定义",
                            selected = selectedFilmLatitudePreset == null,
                            onClick = { onFilmLatitudePresetSelected(null) },
                        )
                        FilmLatitudePreset.entries.forEach { preset ->
                            ChoiceButton(
                                text = preset.displayName,
                                selected = selectedFilmLatitudePreset == preset,
                                onClick = { onFilmLatitudePresetSelected(preset) },
                            )
                        }
                    }
                    selectedFilmLatitudePreset?.let { preset ->
                        SettingHint(
                            "${preset.evidence.displayName} · " +
                                "高光 +%.1f EV / 暗部 -%.1f EV".format(
                                    preset.highlightStops,
                                    preset.shadowStops,
                                ),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    StopControl(
                        label = "高光",
                        value = highlightLatitudeStops,
                        enabled = exposureRiskEnabled,
                        onDecrease = {
                            onHighlightLatitudeChanged(-MeteringViewModel.EV_THIRD_STEP)
                        },
                        onIncrease = {
                            onHighlightLatitudeChanged(MeteringViewModel.EV_THIRD_STEP)
                        },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    StopControl(
                        label = "暗部",
                        value = -shadowLatitudeStops,
                        enabled = exposureRiskEnabled,
                        onDecrease = {
                            onShadowLatitudeChanged(-MeteringViewModel.EV_THIRD_STEP)
                        },
                        onIncrease = {
                            onShadowLatitudeChanged(MeteringViewModel.EV_THIRD_STEP)
                        },
                    )
                    SettingHint(
                        "冻结画面后，按 0.1 EV 精度判断是否超出胶片宽容度。" +
                            "高光风险显示红色、暗部风险显示绿色。" +
                            "经验近似预设不是厂商保证值。",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("保存")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun CollapsibleSettingsSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (expanded) "▲" else "▼",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(
                        start = 14.dp,
                        end = 14.dp,
                        bottom = 14.dp,
                    ),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun PercentageControl(
    label: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDecrease,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("−")
            }
            Text(
                text = "$value%",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = onIncrease,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun StopControl(
    label: String,
    value: Double,
    enabled: Boolean = true,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabled) 1f else 0.42f,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDecrease,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("−")
            }
            Text(
                text = "%+.1f EV".format(value),
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (enabled) 1f else 0.42f,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(
                onClick = onIncrease,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("+")
            }
        }
    }
}

@Composable
private fun SettingHint(text: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun MeteringMode.displayName(): String {
    return when (this) {
        MeteringMode.SPOT -> "点测光"
        MeteringMode.CENTER_WEIGHTED -> "中央重点"
        MeteringMode.AVERAGE -> "平均测光"
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.camera_permission_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
