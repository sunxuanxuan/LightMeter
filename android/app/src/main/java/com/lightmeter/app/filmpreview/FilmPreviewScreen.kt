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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.R
import com.lightmeter.app.ui.CameraPermissionState
import com.lightmeter.app.ui.CameraPreviewView
import com.lightmeter.app.ui.CameraViewfinderMask
import com.lightmeter.app.ui.theme.AppThemeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var cameraSessionId by rememberSaveable { mutableStateOf(0) }
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
        if (permissionState == CameraPermissionState.GRANTED && !state.isFrozen) {
            cameraSessionId += 1
            viewModel.prepareForCameraResume()
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
        cameraSessionId = cameraSessionId,
        onBack = onExit,
        onPresetSelected = viewModel::selectPreset,
        onFilmSelected = viewModel::selectFilm,
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
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val imageResource = presetImageResource(preset)
                    ?: if (preset.id == ManualCameraConfig.MANUAL_PRESET_ID) {
                        R.drawable.bottom_camera_left
                    } else {
                        null
                    }
                if (imageResource != null) {
                    Image(
                        painter = painterResource(imageResource),
                        contentDescription = "${preset.displayName} 参考图",
                        contentScale = if (
                            preset.id == ManualCameraConfig.MANUAL_PRESET_ID
                        ) {
                            ContentScale.Fit
                        } else {
                            ContentScale.Crop
                        },
                        modifier = Modifier
                            .size(width = 112.dp, height = 72.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(width = 112.dp, height = 72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onClick),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = preset.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = preset.parameterSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = preset.flash?.let {
                            "闪光 ${formatDecimal(it.effectiveDistanceMinMeters)}-" +
                                "${formatDecimal(it.effectiveDistanceMaxMeters)} m"
                        } ?: "无闪光参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected && expandedContent != null) {
                Spacer(modifier = Modifier.height(10.dp))
                expandedContent()
            }
        }
    }
}

private fun presetImageResource(preset: DisposableCameraPreset): Int? {
    return when (preset.id) {
        ManualCameraConfig.MANUAL_PRESET_ID -> R.drawable.bottom_camera_left
        "kodak-power-flash-800" -> R.drawable.preset_power_flash
        "kodak-ec35-reusable" -> R.drawable.preset_kodak_ec35
        "fujifilm-quicksnap-flash-400" -> R.drawable.preset_quick_snap
        "fujifilm-c400-jelly" -> R.drawable.preset_c400
        else -> null
    }
}

private fun filmImageResource(film: FilmProfile): Int {
    return when {
        film.name.contains("Fuji", ignoreCase = true) && film.iso == 200 ->
            R.drawable.film_fuji200

        film.name.contains("Fuji", ignoreCase = true) && film.iso == 400 ->
            R.drawable.film_fuji400

        film.name.contains("Kodak", ignoreCase = true) && film.iso == 200 ->
            R.drawable.film_kodak200

        film.name.contains("Kodak", ignoreCase = true) && film.iso == 400 ->
            R.drawable.film_kodak400

        film.iso == 100 -> R.drawable.film_iso100
        film.iso == 200 -> R.drawable.film_iso200
        film.iso == 400 -> R.drawable.film_iso400
        else -> R.drawable.film_iso800
    }
}

@Composable
private fun FilmPreviewWorkspace(
    state: FilmPreviewUiState,
    permissionState: CameraPermissionState,
    cameraSessionId: Int,
    onBack: () -> Unit,
    onPresetSelected: (String) -> Unit,
    onFilmSelected: (String) -> Unit,
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
    var selector by rememberSaveable { mutableStateOf<PreviewSelector?>(null) }
    var hasSelectedCameraImage by rememberSaveable {
        mutableStateOf(state.hasSavedPresetSelection)
    }
    var hasSelectedFilmImage by rememberSaveable {
        mutableStateOf(state.hasSavedPresetSelection)
    }
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var simulatedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var frozenSnapshot by remember { mutableStateOf<ExposureSnapshot?>(null) }
    var comparisonSplit by rememberSaveable { mutableStateOf(0.5f) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraOptics by remember { mutableStateOf<CameraOptics?>(null) }
    var cameraZoomState by remember { mutableStateOf(CameraZoomState()) }
    LaunchedEffect(cameraSessionId) {
        cameraOptics = null
        cameraZoomState = CameraZoomState()
    }
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
                        baseGrainIntensity = preset.film.baseGrainIntensity,
                    )
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(state.isFrozen) {
        if (!state.isFrozen) {
            frozenFrame = null
            simulatedFrame = null
            frozenSnapshot = null
        }
    }
    LaunchedEffect(state.isFrozen, state.freezeRequestId) {
        comparisonSplit = 0.5f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PreviewTopControls(
                onBack = onBack,
                onOpenSettings = { showsSettings = true },
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
                        .clip(previewShape)
                        .onSizeChanged { previewSize = it }
                        .border(
                            1.dp,
                            Color(0xFFD3AA5F).copy(alpha = 0.72f),
                            previewShape,
                        ),
                ) {
                when (permissionState) {
                    CameraPermissionState.GRANTED -> key(cameraSessionId) {
                        CameraPreviewView(
                        meteringConfig = MeteringConfig(
                            mode = MeteringMode.CENTER_CROP_AVERAGE,
                            centerCropPercent = 60,
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
                    }

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
                    val source = frozenFrame
                    val simulated = simulatedFrame
                    when {
                        source != null && simulated != null -> {
                            FilmSimulationComparison(
                                source = source,
                                simulated = simulated,
                                splitFraction = comparisonSplit,
                                onSplitChanged = { comparisonSplit = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        source != null -> {
                            Image(
                                bitmap = source.asImageBitmap(),
                                contentDescription = "冻结的手机画面",
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize(),
                            )
                            SimulationLoadingOverlay(modifier = Modifier.fillMaxSize())
                        }
                    }
                } else {
                    CameraViewfinderMask(
                        viewfinder = normalizedViewfinder,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                }
            }

            ExposureAdvicePanel(
                text = previewGuidance(
                    isFrozen = state.isFrozen,
                    isSimulationReady = simulatedFrame != null,
                    adviceCode = state.evaluation?.adviceCode,
                    preset = preset,
                ),
                statusPrefix = adviceStatusPrefix(state.evaluation?.rating),
                textColor = adviceTextColor(state.evaluation?.rating),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )

            SimulationBottomBar(
                cameraImageResource = if (hasSelectedCameraImage) {
                    presetImageResource(preset) ?: R.drawable.bottom_camera_left
                } else {
                    R.drawable.bottom_camera_left
                },
                filmImageResource = if (hasSelectedFilmImage) {
                    filmImageResource(preset.film)
                } else {
                    R.drawable.bottom_film_right
                },
                isFrozen = state.isFrozen,
                captureEnabled = state.isFrozen ||
                    (
                        state.isCameraReady &&
                            state.meteredEv100 != null &&
                            isZoomReady
                        ),
                onCameraClick = { selector = PreviewSelector.CAMERA },
                onCaptureClick = if (state.isFrozen) onResumeLive else onFreezePreview,
                onFilmClick = { selector = PreviewSelector.FILM },
            )
        }

        selector?.let { activeSelector ->
            PresetSelectorSheet(
                selector = activeSelector,
                presets = state.presets,
                selectedPreset = preset,
                onDismiss = { selector = null },
                onPresetSelected = { selected ->
                    onPresetSelected(selected.id)
                    hasSelectedCameraImage = true
                    selector = null
                },
                onFilmSelected = { film ->
                    onFilmSelected(film.id)
                    hasSelectedFilmImage = true
                    selector = null
                },
            )
        }
    }

    if (showsSettings) {
        PresetSettingsDialog(
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
private fun SimulationBottomBar(
    cameraImageResource: Int,
    filmImageResource: Int,
    isFrozen: Boolean,
    captureEnabled: Boolean,
    onCameraClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onFilmClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomPresetButton(
                imageResource = cameraImageResource,
                label = "机型",
                onClick = onCameraClick,
                modifier = Modifier.weight(1f),
            )
            PreviewFreezeButton(
                isFrozen = isFrozen,
                enabled = captureEnabled,
                onClick = onCaptureClick,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            BottomPresetButton(
                imageResource = filmImageResource,
                label = "底片",
                onClick = onFilmClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomPresetButton(
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
private fun ExposureAdvicePanel(
    text: String,
    statusPrefix: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = statusPrefix + text,
            color = textColor,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Left,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FilmSimulationComparison(
    source: Bitmap,
    simulated: Bitmap,
    splitFraction: Float,
    onSplitChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sourceImage = remember(source) { source.asImageBitmap() }
    val simulatedImage = remember(simulated) { simulated.asImageBitmap() }
    val latestSplitFraction by rememberUpdatedState(splitFraction)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            var dragSplitFraction = latestSplitFraction
            detectDragGestures(
                onDragStart = { dragSplitFraction = latestSplitFraction },
            ) { change, dragAmount ->
                change.consume()
                if (size.width > 0) {
                    dragSplitFraction = (
                        dragSplitFraction + dragAmount.x / size.width
                    ).coerceIn(COMPARISON_MIN_SPLIT, COMPARISON_MAX_SPLIT)
                    onSplitChanged(dragSplitFraction)
                }
            }
        },
    ) {
        val destinationSize = IntSize(
            width = size.width.roundToInt(),
            height = size.height.roundToInt(),
        )
        val splitX = size.width * splitFraction

        drawImage(sourceImage, dstSize = destinationSize)
        clipRect(left = splitX) {
            drawImage(simulatedImage, dstSize = destinationSize)
        }
        drawLine(
            color = Color.White,
            start = Offset(splitX, 0f),
            end = Offset(splitX, size.height),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(
            color = Color.White,
            radius = 18.dp.toPx(),
            center = Offset(splitX, size.height / 2f),
        )
        drawCircle(
            color = Color(0xFF222222),
            radius = 15.dp.toPx(),
            center = Offset(splitX, size.height / 2f),
        )
        drawLine(
            color = Color.White,
            start = Offset(splitX - 6.dp.toPx(), size.height / 2f),
            end = Offset(splitX + 6.dp.toPx(), size.height / 2f),
            strokeWidth = 2.dp.toPx(),
        )
    }
    ComparisonLabels(modifier = Modifier.fillMaxSize())
}

@Composable
private fun ComparisonLabels(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(10.dp)) {
        ComparisonLabel(
            text = "手机画面",
            modifier = Modifier.align(Alignment.TopStart),
        )
        ComparisonLabel(
            text = "模拟成片",
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun ComparisonLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.56f),
        shape = RoundedCornerShape(5.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SimulationLoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "正在生成成片模拟",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private enum class PreviewSelector {
    CAMERA,
    FILM,
}

@Composable
private fun PresetSelectorSheet(
    selector: PreviewSelector,
    presets: List<DisposableCameraPreset>,
    selectedPreset: DisposableCameraPreset,
    onDismiss: () -> Unit,
    onPresetSelected: (DisposableCameraPreset) -> Unit,
    onFilmSelected: (FilmProfile) -> Unit,
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
                    text = if (selector == PreviewSelector.CAMERA) "选择机型" else "底片",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 12.dp),
                )
                if (selector == PreviewSelector.CAMERA) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(presets, key = { it.id }) { candidate ->
                            CameraPresetOption(
                                preset = candidate,
                                selected = candidate.id == selectedPreset.id,
                                onClick = { onPresetSelected(candidate) },
                            )
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(selectedPreset.compatibleFilms, key = { it.id }) { film ->
                            FilmPresetOption(
                                film = film,
                                selected = film.id == selectedPreset.film.id,
                                isSelectable = selectedPreset.isFilmSelectable,
                                onClick = { onFilmSelected(film) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPresetOption(
    preset: DisposableCameraPreset,
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
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            presetImageResource(preset)?.let { image ->
                Image(
                    painter = painterResource(image),
                    contentDescription = preset.displayName,
                    contentScale = if (preset.id == ManualCameraConfig.MANUAL_PRESET_ID) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } ?: Icon(
                imageVector = Icons.Outlined.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun FilmPresetOption(
    film: FilmProfile,
    selected: Boolean,
    isSelectable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(width = 144.dp, height = 142.dp)
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Image(
                painter = painterResource(filmImageResource(film)),
                contentDescription = film.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
            )
            Text(
                text = film.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(
                text = if (isSelectable) "ISO ${film.iso}" else "内置，不可更换",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(25.dp),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onOpenSettings),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewFreezeButton(
    isFrozen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isCaptureFeedbackVisible by remember { mutableStateOf(false) }
    val innerRingScale by animateFloatAsState(
        targetValue = if (isCaptureFeedbackVisible) 0.66f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "captureInnerRingScale",
    )

    LaunchedEffect(isCaptureFeedbackVisible) {
        if (isCaptureFeedbackVisible) {
            delay(CAPTURE_FEEDBACK_DURATION_MS)
            isCaptureFeedbackVisible = false
            onClick()
        }
    }

    if (isFrozen) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier
                .size(CONTROL_BUTTON_SIZE)
                .clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "退出冻结，恢复取景",
                    tint = Color.Gray,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(CONTROL_BUTTON_SIZE)
                .background(
                    color = CAPTURE_RED,
                    shape = CircleShape,
                )
                .border(
                    width = 3.dp,
                    color = Color.White,
                    shape = CircleShape,
                )
                .clip(CircleShape)
                .clickable(enabled = enabled && !isCaptureFeedbackVisible) {
                    isCaptureFeedbackVisible = true
                },
        ) {
            Box(
                modifier = Modifier
                    .size(53.dp)
                    .scale(innerRingScale)
                    .background(
                        color = Color.White,
                        shape = CircleShape,
                    ),
            )
        }
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
    manualConfig: ManualCameraConfig,
    themeStyle: AppThemeStyle,
    onThemeStyleChanged: (AppThemeStyle) -> Unit,
    onSave: (String, ManualCameraConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var manualEditorExpanded by rememberSaveable {
        mutableStateOf(true)
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
    val manualConfigToSave = if (manualConfigValid) {
        pendingManualConfig
    } else {
        manualConfig
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
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
                    text = "内置机型请在底部“机型”按钮中选择。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                PresetCard(
                    preset = manualConfig.toPreset(),
                    selected = true,
                    onClick = { manualEditorExpanded = !manualEditorExpanded },
                    expandedContent = if (manualEditorExpanded) {
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ManualCameraConfig.MANUAL_PRESET_ID,
                        manualConfigToSave,
                    )
                },
                enabled = manualConfigValid,
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

private fun previewGuidance(
    isFrozen: Boolean,
    isSimulationReady: Boolean,
    adviceCode: PreviewAdviceCode?,
    preset: DisposableCameraPreset,
): String {
    if (isFrozen && !isSimulationReady) {
        return "正在根据当前机型和底片生成成片模拟。"
    }
    return when (adviceCode) {
        PreviewAdviceCode.USE_FLASH -> preset.flash?.let {
            "画面过暗，建议开启闪光灯，并让主体保持在 " +
                "${formatDecimal(it.effectiveDistanceMinMeters)}-" +
                "${formatDecimal(it.effectiveDistanceMaxMeters)} m 内。"
        } ?: "画面过暗，建议到光线更充足的地方拍摄。"

        PreviewAdviceCode.AMBIENT_TOO_DARK -> "画面偏暗，建议到光线更充足的地方拍摄。"
        PreviewAdviceCode.AMBIENT_TOO_BRIGHT -> "画面偏亮，建议到光线稍暗的地方拍摄。"
        PreviewAdviceCode.SEEK_SHADE -> "画面过亮，建议移到阴影处拍摄。"
        PreviewAdviceCode.SUITABLE -> "画面亮度合适，可以正常拍摄。"
        PreviewAdviceCode.UNAVAILABLE,
        null,
        -> "正在分析画面亮度。"
    }
}

private fun adviceStatusPrefix(rating: PreviewSceneRating?): String {
    return when (rating) {
        PreviewSceneRating.GOOD -> "✅ "
        PreviewSceneRating.CAUTION -> "！ "
        PreviewSceneRating.POOR -> "❌ "
        PreviewSceneRating.UNAVAILABLE,
        null,
        -> ""
    }
}

@Composable
private fun adviceTextColor(rating: PreviewSceneRating?): Color {
    return when (rating) {
        PreviewSceneRating.GOOD -> Color(0xFF3FAE5A)
        PreviewSceneRating.CAUTION -> Color(0xFFE08A19)
        PreviewSceneRating.POOR -> Color(0xFFD84343)
        PreviewSceneRating.UNAVAILABLE,
        null,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private const val PREVIEW_ASPECT_RATIO = 2f / 3f
private const val PREVIEW_ZOOM_TOLERANCE = 0.02f
private const val COMPARISON_MIN_SPLIT = 0f
private const val COMPARISON_MAX_SPLIT = 1f
private val CONTROL_BUTTON_SIZE = 75.dp
private const val CAPTURE_FEEDBACK_DURATION_MS = 140L
private val CAPTURE_RED = Color(0xFFD84343)

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
