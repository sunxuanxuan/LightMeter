package com.lightmeter.app.filmpreview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.camera.CameraZoomState
import com.lightmeter.app.camera.ViewfinderProjection
import com.lightmeter.app.camera.ViewfinderProjectionCalculator
import com.lightmeter.app.exposure.FrameFormat
import com.lightmeter.app.metering.ExposureRiskCalculator
import com.lightmeter.app.metering.ExposureRiskMask
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.ui.CameraPermissionState
import com.lightmeter.app.ui.CameraPreviewView
import com.lightmeter.app.ui.CameraViewfinderMask
import com.lightmeter.app.ui.theme.AppThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun FilmPreviewRoute(
    onExit: () -> Unit,
    calibrationOffset: Double = 0.0,
    themeStyle: AppThemeStyle = AppThemeStyle.DARK,
    onThemeStyleChanged: (AppThemeStyle) -> Unit = {},
) {
    val context = LocalContext.current
    val settingsStore = remember(context) {
        SharedPreferencesFilmPreviewSettingsStore(context.applicationContext)
    }
    val viewModelFactory = remember(settingsStore) {
        FilmPreviewViewModelFactory(settingsStore)
    }
    val viewModel: FilmPreviewViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = remember(context) { context.findHostActivity() }
    var permissionState by remember {
        mutableStateOf(
            if (context.hasCameraPermission()) {
                CameraPermissionState.GRANTED
            } else {
                CameraPermissionState.UNKNOWN
            },
        )
    }
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionState = when {
            granted -> CameraPermissionState.GRANTED
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CAMERA,
            ) -> CameraPermissionState.DENIED
            hasRequestedPermission -> CameraPermissionState.PERMANENTLY_DENIED
            else -> CameraPermissionState.DENIED
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionState = if (context.hasCameraPermission()) {
            CameraPermissionState.GRANTED
        } else if (hasRequestedPermission) {
            permissionState
        } else {
            CameraPermissionState.DENIED
        }
    }

    LaunchedEffect(calibrationOffset) {
        viewModel.invalidateMetering()
    }

    BackHandler {
        onExit()
    }

    FilmPreviewWorkspace(
        state = state,
        permissionState = permissionState,
        onBack = onExit,
        onPresetSettingsSaved = viewModel::savePresetSettings,
        onRequestPermission = {
            hasRequestedPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        },
        onOpenSettings = { context.openHostAppSettings() },
        onCameraReady = viewModel::onCameraReady,
        onCameraError = viewModel::onCameraError,
        onMeteringResult = viewModel::onMeteringResult,
        onFrozenSnapshot = viewModel::onFrozenSnapshot,
        onFreezePreview = viewModel::freezePreview,
        onResumeLive = viewModel::resumeLivePreview,
        onFreezeCaptureFailed = viewModel::onFreezeCaptureFailed,
        calibrationOffset = calibrationOffset,
        themeStyle = themeStyle,
        onThemeStyleChanged = onThemeStyleChanged,
    )
}

@Composable
private fun PresetCard(
    preset: DisposableCameraPreset,
    selected: Boolean,
    onClick: () -> Unit,
    expandedContent: @Composable (() -> Unit)? = null,
) {
    var detailsExpanded by rememberSaveable(preset.id) { mutableStateOf(false) }
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
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
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (detailsExpanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = if (detailsExpanded) {
                        "收起参数"
                    } else {
                        "展开参数"
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable { detailsExpanded = !detailsExpanded }
                        .padding(4.dp),
                )
            }
            if (detailsExpanded) {
                Text(
                    text = preset.parameterSummary(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
                val flashText = preset.flash?.let {
                    "闪光有效距离 ${formatDecimal(it.effectiveDistanceMinMeters)}-" +
                        "${formatDecimal(it.effectiveDistanceMaxMeters)} m"
                } ?: "无闪光参数"
                Text(
                    text = flashText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                preset.regionOrBatch?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                expandedContent?.let { content ->
                    Spacer(modifier = Modifier.height(10.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun FilmPreviewWorkspace(
    state: FilmPreviewUiState,
    permissionState: CameraPermissionState,
    onBack: () -> Unit,
    onPresetSettingsSaved: (String, ManualCameraConfig) -> Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onCameraReady: () -> Unit,
    onCameraError: (Throwable) -> Unit,
    onMeteringResult: (com.lightmeter.app.metering.MeteringResult) -> Unit,
    onFrozenSnapshot: (Int, ExposureSnapshot) -> Unit,
    onFreezePreview: () -> Unit,
    onResumeLive: () -> Unit,
    onFreezeCaptureFailed: () -> Unit,
    calibrationOffset: Double,
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
) {
    val preset = state.selectedPreset ?: return
    var showsSettings by rememberSaveable { mutableStateOf(false) }
    var isExposureSimulationEnabled by rememberSaveable { mutableStateOf(false) }
    var frozenChromeVisible by rememberSaveable { mutableStateOf(true) }
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var simulatedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var frozenSnapshot by remember { mutableStateOf<ExposureSnapshot?>(null) }
    var exposureRiskMask by remember { mutableStateOf<ExposureRiskMask?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraOptics by remember { mutableStateOf<CameraOptics?>(null) }
    var cameraZoomState by remember { mutableStateOf(CameraZoomState()) }
    val presetReferenceEv100 = remember(preset) {
        FilmPreviewEngine.presetEv100(preset)
    }
    val projection = remember(previewSize, preset, cameraOptics) {
        if (previewSize.width == 0 || previewSize.height == 0 || cameraOptics == null) {
            ViewfinderProjection(1.0, 1.0)
        } else {
            ViewfinderProjectionCalculator.calculate(
                previewAspectRatio = previewSize.width / previewSize.height.toDouble(),
                frameFormat = FrameFormat.FILM_135,
                targetFocalLengthMm = preset.optics.focalLengthMm,
                cameraOptics = requireNotNull(cameraOptics),
            )
        }
    }
    val targetZoomRatio = projection.fitZoomRatio.toFloat()
    val effectiveZoomRatio = targetZoomRatio.coerceIn(
        cameraZoomState.minZoomRatio,
        cameraZoomState.maxZoomRatio,
    )
    val isZoomReady = cameraOptics != null &&
        cameraZoomState.isInitialized &&
        abs(cameraZoomState.zoomRatio - effectiveZoomRatio) <= PREVIEW_ZOOM_TOLERANCE
    val normalizedViewfinder = remember(projection, effectiveZoomRatio) {
        projection.viewfinderAt(effectiveZoomRatio.toDouble())
    }
    val presetRevision = remember(
        preset.id,
        preset.presetVersion,
        cameraOptics,
        calibrationOffset,
    ) {
        listOf(
            preset.id,
            preset.presetVersion,
            cameraOptics,
            calibrationOffset,
        ).hashCode().toLong()
    }

    LaunchedEffect(
        frozenSnapshot,
        normalizedViewfinder,
        presetReferenceEv100,
        preset.film.highlightLatitudeStops,
        preset.film.shadowLatitudeStops,
    ) {
        val snapshot = frozenSnapshot
        exposureRiskMask = if (snapshot == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                ExposureRiskCalculator.calculate(
                    exposureMap = snapshot.exposureMap,
                    viewfinder = normalizedViewfinder,
                    referenceEv100 = presetReferenceEv100,
                    highlightLatitudeStops = preset.film.highlightLatitudeStops,
                    shadowLatitudeStops = preset.film.shadowLatitudeStops,
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
        frozenSnapshot,
        presetReferenceEv100,
        preset.film.highlightLatitudeStops,
        preset.film.shadowLatitudeStops,
    ) {
        val source = frozenFrame
        val snapshot = frozenSnapshot
        simulatedFrame = if (source == null || snapshot == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching {
                    FilmExposureSimulator.render(
                        source = source,
                        exposureMap = snapshot.exposureMap,
                        referenceEv100 = presetReferenceEv100,
                        highlightLatitudeStops = preset.film.highlightLatitudeStops,
                        shadowLatitudeStops = preset.film.shadowLatitudeStops,
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
            frozenSnapshot = null
            exposureRiskMask = null
        }
    }
    LaunchedEffect(state.isFrozen, state.freezeRequestId) {
        frozenChromeVisible = true
        isExposureSimulationEnabled = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                    .clip(previewShape)
                    .onSizeChanged { previewSize = it }
                    .border(
                        1.dp,
                        Color(0xFFD3AA5F).copy(alpha = 0.72f),
                        previewShape,
                    ),
            ) {
                when (permissionState) {
                    CameraPermissionState.GRANTED -> CameraPreviewView(
                        meteringConfig = MeteringConfig(
                            mode = MeteringMode.CENTER_WEIGHTED,
                            centerAreaPercent = PREVIEW_CENTER_AREA_PERCENT,
                            centerWeightPercent = PREVIEW_CENTER_WEIGHT_PERCENT,
                            viewfinderRect = normalizedViewfinder,
                            previewAspectRatio = PREVIEW_ASPECT_RATIO.toDouble(),
                            targetZoomRatio = effectiveZoomRatio.toDouble(),
                            isZoomReady = isZoomReady,
                            revision = presetRevision,
                            calibrationOffset = calibrationOffset,
                        ),
                        targetZoomRatio = if (state.isFrozen) {
                            cameraZoomState.zoomRatio
                        } else {
                            targetZoomRatio
                        },
                        freezeRequestId = state.freezeRequestId,
                        shouldCaptureFrame = state.isFrozen,
                        onMeteringResult = {
                            if (it.revision == presetRevision) {
                                onMeteringResult(it)
                            }
                        },
                        onFrameCaptured = { capturedFrame ->
                            val requestId = capturedFrame?.requestId
                            val bitmap = capturedFrame?.bitmap
                            val snapshot = capturedFrame?.snapshot
                            if (
                                requestId == null ||
                                bitmap == null ||
                                snapshot == null ||
                                snapshot.revision != presetRevision
                            ) {
                                onFreezeCaptureFailed()
                            } else {
                                onFrozenSnapshot(requestId, snapshot)
                                frozenFrame = bitmap
                                simulatedFrame = null
                                frozenSnapshot = snapshot
                            }
                        },
                        onOpticsAvailable = { cameraOptics = it },
                        onZoomStateChanged = { cameraZoomState = it },
                        onReady = onCameraReady,
                        onError = onCameraError,
                        modifier = Modifier.fillMaxSize(),
                    )

                    CameraPermissionState.PERMANENTLY_DENIED -> PreviewPermissionContent(
                        buttonText = "打开系统设置",
                        onClick = onOpenSettings,
                    )

                    CameraPermissionState.UNKNOWN,
                    CameraPermissionState.DENIED,
                    -> PreviewPermissionContent(
                        buttonText = "授予相机权限",
                        onClick = onRequestPermission,
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
                                "一次性相机曝光结果模拟"
                            } else {
                                "一次性相机冻结预览"
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
                                contentDescription = "胶片宽容度风险预警",
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        exposureRiskMask?.let { riskMask ->
                            FilmRiskLegend(
                                riskMask = riskMask,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp),
                            )
                        }
                    }
                }

                CameraViewfinderMask(
                    viewfinder = normalizedViewfinder,
                    modifier = Modifier.fillMaxSize(),
                )

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

                if (!state.isFrozen || frozenChromeVisible) {
                    PreviewTopControls(
                        onBack = onBack,
                        onOpenSettings = { showsSettings = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(10.dp),
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PreviewFreezeButton(
                            isFrozen = state.isFrozen,
                            enabled = state.isFrozen ||
                                (
                                    state.isCameraReady &&
                                        state.meteredEv100 != null &&
                                        isZoomReady
                                    ),
                            onClick = if (state.isFrozen) onResumeLive else onFreezePreview,
                        )
                        if (
                            state.isFrozen &&
                            exposureRiskMask != null &&
                            simulatedFrame != null
                        ) {
                            ExposureSimulationToggle(
                                isSimulationEnabled = isExposureSimulationEnabled,
                                onClick = {
                                    isExposureSimulationEnabled =
                                        !isExposureSimulationEnabled
                                },
                            )
                        }
                    }
                }
            }
        }

        PreviewStatusPanel(
            preset = preset,
            state = state,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showsSettings) {
        PresetSettingsDialog(
            presets = state.presets,
            selectedPreset = preset,
            manualConfig = state.manualConfig,
            themeStyle = themeStyle,
            onThemeStyleChanged = onThemeStyleChanged,
            onSave = { presetId, config ->
                onPresetSettingsSaved(presetId, config).also { saved ->
                    if (saved) showsSettings = false
                }
            },
            onDismiss = { showsSettings = false },
        )
    }
}

@Composable
private fun PreviewStatusPanel(
    preset: DisposableCameraPreset,
    state: FilmPreviewUiState,
    modifier: Modifier = Modifier,
) {
    val evaluation = state.evaluation
    val presetEv100 = evaluation?.presetEv100 ?: FilmPreviewEngine.presetEv100(preset)
    val sceneEv100 = evaluation?.sceneDeltaEv?.let { presetEv100 + it }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                text = preset.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "参数由预设锁定",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "胶片预设固定曝光：${formatEv(presetEv100)}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "场景推荐曝光：${sceneEv100?.let(::formatEv) ?: "--"}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "相对差值：${
                    evaluation?.sceneDeltaEv?.let(::formatSignedStops) ?: "--"
                }",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = adviceText(evaluation?.adviceCode, preset),
                color = when (evaluation?.adviceCode) {
                    PreviewAdviceCode.SUITABLE,
                    PreviewAdviceCode.UNAVAILABLE,
                    null,
                    -> MaterialTheme.colorScheme.onSurfaceVariant

                    else -> MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadOnlyParameter(
                    label = "ISO",
                    value = preset.film.iso.toString(),
                    modifier = Modifier.weight(1f),
                )
                ReadOnlyParameter(
                    label = "光圈",
                    value = "f/${formatDecimal(preset.optics.aperture)}",
                    modifier = Modifier.weight(1f),
                )
                ReadOnlyParameter(
                    label = "快门",
                    value = shutterLabel(preset.shutterSeconds),
                    modifier = Modifier.weight(1f),
                )
                ReadOnlyParameter(
                    label = "焦距",
                    value = "${formatDecimal(preset.optics.focalLengthMm)} mm",
                    modifier = Modifier.weight(1f),
                )
            }
            state.errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewTopControls(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.68f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier.clickable(onClick = onBack),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(
                    horizontal = (14 * PREVIEW_CHROME_SCALE).dp,
                    vertical = (10 * PREVIEW_CHROME_SCALE).dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.GridView,
                    contentDescription = null,
                    tint = Color(0xFFD3AA5F),
                    modifier = Modifier.size((22 * PREVIEW_CHROME_SCALE).dp),
                )
                Text(
                    text = "模式",
                    color = Color(0xFFD3AA5F),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Surface(
            color = Color.Black.copy(alpha = 0.68f),
            shape = CircleShape,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            modifier = Modifier
                .size((48 * PREVIEW_CHROME_SCALE).dp)
                .clickable(onClick = onOpenSettings),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = Color(0xFFD3AA5F),
                    modifier = Modifier.size((24 * PREVIEW_CHROME_SCALE).dp),
                )
            }
        }
    }
}

@Composable
private fun FilmRiskLegend(
    riskMask: ExposureRiskMask,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.68f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilmRiskLegendItem(
                color = Color(0xFFFF2D2D),
                label = "高光 %.1f%%".format(riskMask.highlightRatio * 100.0),
            )
            FilmRiskLegendItem(
                color = Color(0xFF00D26A),
                label = "暗部 %.1f%%".format(riskMask.shadowRatio * 100.0),
            )
        }
    }
}

@Composable
private fun FilmRiskLegendItem(
    color: Color,
    label: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
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
private fun PreviewFreezeButton(
    isFrozen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier.size(52.dp),
    ) {
        Text(
            text = if (isFrozen) "▶" else "⏸",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ExposureSimulationToggle(
    isSimulationEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSimulationEnabled) {
                Color(0xFFD3AA5F)
            } else {
                Color.White
            },
            contentColor = Color.Black,
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(52.dp),
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
private fun ReadOnlyParameter(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
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
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PresetSettingsDialog(
    presets: List<DisposableCameraPreset>,
    selectedPreset: DisposableCameraPreset,
    manualConfig: ManualCameraConfig,
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onSave: (String, ManualCameraConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingPresetId by rememberSaveable(selectedPreset.id) {
        mutableStateOf(selectedPreset.id)
    }
    var manualIso by rememberSaveable(manualConfig) {
        mutableStateOf(manualConfig.iso.toString())
    }
    var manualShutter by rememberSaveable(manualConfig) {
        mutableStateOf(manualConfig.shutterDenominator.toString())
    }
    var manualAperture by rememberSaveable(manualConfig) {
        mutableStateOf(formatDecimal(manualConfig.aperture))
    }
    var manualFocalLength by rememberSaveable(manualConfig) {
        mutableStateOf(formatDecimal(manualConfig.focalLengthMm))
    }
    val pendingManualConfig = ManualCameraConfig(
        iso = manualIso.toIntOrNull() ?: 0,
        shutterDenominator = manualShutter.toIntOrNull() ?: 0,
        aperture = manualAperture.toDoubleOrNull() ?: Double.NaN,
        focalLengthMm = manualFocalLength.toDoubleOrNull() ?: Double.NaN,
    )
    val manualConfigValid = pendingManualConfig.isValid()
    val selectedManualPreset =
        pendingPresetId == ManualCameraConfig.MANUAL_PRESET_ID
    val manualConfigToSave = if (manualConfigValid) {
        pendingManualConfig
    } else {
        manualConfig
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("一次性相机预设") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "外观",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeStyle.entries
                        .filter(AppThemeStyle::available)
                        .forEach { style ->
                            OutlinedButton(
                                onClick = { onThemeStyleChanged(style) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (themeStyle == style) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                            ) {
                                Text(style.displayName)
                            }
                        }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "曝光参数由预设提供，预览模式中不可单独修改。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                presets.forEach { preset ->
                    PresetCard(
                        preset = preset,
                        selected = preset.id == pendingPresetId,
                        onClick = { pendingPresetId = preset.id },
                        expandedContent = if (preset.id == ManualCameraConfig.MANUAL_PRESET_ID) {
                            {
                                ManualCameraConfigEditor(
                                    iso = manualIso,
                                    shutterDenominator = manualShutter,
                                    aperture = manualAperture,
                                    focalLengthMm = manualFocalLength,
                                    isValid = manualConfigValid,
                                    onIsoChanged = { manualIso = it },
                                    onShutterChanged = { manualShutter = it },
                                    onApertureChanged = { manualAperture = it },
                                    onFocalLengthChanged = { manualFocalLength = it },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(pendingPresetId, manualConfigToSave) },
                enabled = !selectedManualPreset || manualConfigValid,
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun ManualCameraConfigEditor(
    iso: String,
    shutterDenominator: String,
    aperture: String,
    focalLengthMm: String,
    isValid: Boolean,
    onIsoChanged: (String) -> Unit,
    onShutterChanged: (String) -> Unit,
    onApertureChanged: (String) -> Unit,
    onFocalLengthChanged: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "手动参数",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualParameterField(
                    value = iso,
                    label = "ISO",
                    onValueChanged = onIsoChanged,
                    modifier = Modifier.weight(1f),
                )
                ManualParameterField(
                    value = shutterDenominator,
                    label = "快门 1/x 秒",
                    onValueChanged = onShutterChanged,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualParameterField(
                    value = aperture,
                    label = "光圈 f/",
                    onValueChanged = onApertureChanged,
                    decimal = true,
                    modifier = Modifier.weight(1f),
                )
                ManualParameterField(
                    value = focalLengthMm,
                    label = "焦段 mm",
                    onValueChanged = onFocalLengthChanged,
                    decimal = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (isValid) {
                    "支持 ISO 25-6400、快门 1-1/8000 秒、f/1-f/64、20-150mm"
                } else {
                    "请检查参数范围和数字格式"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isValid) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun ManualParameterField(
    value: String,
    label: String,
    onValueChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val filtered = input.filterIndexed { index, character ->
                character.isDigit() || (decimal && character == '.' && index > 0)
            }
            if (filtered.count { it == '.' } <= 1) {
                onValueChanged(filtered)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) {
                KeyboardType.Decimal
            } else {
                KeyboardType.Number
            },
        ),
        modifier = modifier,
    )
}

@Composable
private fun PreviewPermissionContent(
    buttonText: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "胶片预演需要读取相机画面和现场亮度",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(buttonText)
        }
    }
}

private fun DisposableCameraPreset.parameterSummary(): String {
    return "ISO ${film.iso} · f/${formatDecimal(optics.aperture)} · " +
        shutterLabel(shutterSeconds) + " · ${formatDecimal(optics.focalLengthMm)} mm"
}

private fun shutterLabel(seconds: Double): String {
    if (seconds >= 1.0) return "${formatDecimal(seconds)} s"
    return "1/${(1.0 / seconds).roundToInt()} s"
}

private fun formatDecimal(value: Double): String {
    return if (value == value.roundToInt().toDouble()) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

private fun formatSignedStops(value: Double): String {
    return String.format(Locale.US, "%+.1f EV", value)
}

private fun formatEv(value: Double): String {
    return String.format(Locale.US, "%.1f EV", value)
}

private fun adviceText(
    code: PreviewAdviceCode?,
    preset: DisposableCameraPreset,
): String {
    return when (code) {
        PreviewAdviceCode.SUITABLE -> "当前环境亮度适合这组固定参数。"
        PreviewAdviceCode.USE_FLASH -> preset.flash?.let {
            "环境偏暗，建议开启闪光灯并让主体保持在 " +
                "${formatDecimal(it.effectiveDistanceMinMeters)}-" +
                "${formatDecimal(it.effectiveDistanceMaxMeters)} m。"
        } ?: "环境偏暗，建议增加现场光线。"

        PreviewAdviceCode.AMBIENT_TOO_DARK -> "环境超出暗部宽容度，成片可能明显欠曝。"
        PreviewAdviceCode.AMBIENT_TOO_BRIGHT -> "环境超出高光宽容度，亮部细节可能丢失。"
        PreviewAdviceCode.UNAVAILABLE,
        null,
        -> "稳定后将显示固定曝光下的场景判断。"
    }
}

private const val PREVIEW_ASPECT_RATIO = 2f / 3f
private const val PREVIEW_ZOOM_TOLERANCE = 0.02f
private const val PREVIEW_CENTER_AREA_PERCENT = 30
private const val PREVIEW_CENTER_WEIGHT_PERCENT = 70
private const val PREVIEW_CHROME_SCALE = 0.75f

private fun Context.hasCameraPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED
}

private tailrec fun Context.findHostActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
}

private fun Context.openHostAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}
