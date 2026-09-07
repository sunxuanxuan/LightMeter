package com.lightmeter.app.filmpreview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.launch
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
        if (state.isFrozen) {
            viewModel.resumeLivePreview()
        } else {
            onExit()
        }
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preset = state.selectedPreset ?: return
    var showsSettings by rememberSaveable { mutableStateOf(false) }
    var selector by rememberSaveable { mutableStateOf<PreviewSelector?>(null) }
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }
    var simulatedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var frozenSnapshot by remember { mutableStateOf<ExposureSnapshot?>(null) }
    var comparisonSplit by rememberSaveable { mutableStateOf(0.5f) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var cameraOptics by remember { mutableStateOf<CameraOptics?>(null) }
    var cameraZoomState by remember { mutableStateOf(CameraZoomState()) }
    var isSavingPhoto by remember { mutableStateOf(false) }
    var pendingLegacySave by remember { mutableStateOf<Bitmap?>(null) }

    fun savePhoto(bitmap: Bitmap) {
        if (isSavingPhoto) return
        isSavingPhoto = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                FilmPhotoSaver.save(context.applicationContext, bitmap)
            }
            isSavingPhoto = false
            if (result.isSuccess) {
                Toast.makeText(context, "照片已保存到相册", Toast.LENGTH_SHORT).show()
                onResumeLive()
            } else {
                Toast.makeText(context, "照片保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val bitmap = pendingLegacySave
        pendingLegacySave = null
        if (granted && bitmap != null) {
            savePhoto(bitmap)
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存照片", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestPhotoSave() {
        val bitmap = simulatedFrame ?: return
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            pendingLegacySave = bitmap
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            savePhoto(bitmap)
        }
    }

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
        preset.film.look,
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
                        filmLook = preset.film.look,
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
            .background(PREVIEW_PANEL_COLOR),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PreviewTopControls(
                onBack = if (state.isFrozen) onResumeLive else onBack,
                onOpenSettings = { showsSettings = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PREVIEW_TOOLBAR_GREEN)
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(PREVIEW_PANEL_COLOR),
            ) {
                val compact = maxHeight < 650.dp
                val controlPanelHeight = if (compact) 184.dp else 196.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(PREVIEW_TOOLBAR_GREEN),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(PREVIEW_ASPECT_RATIO)
                        .offset(y = (-1).dp)
                        .background(Color.Black)
                        .onSizeChanged { previewSize = it }
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

                PreviewControlPanel(
                    preset = preset,
                    cameraImageResource = presetImageResource(preset)
                        ?: R.drawable.bottom_camera_left,
                    filmImageResource = filmImageResource(preset.film),
                    isFrozen = state.isFrozen,
                    isResultReady = simulatedFrame != null,
                    isSavingPhoto = isSavingPhoto,
                    captureEnabled = state.isFrozen ||
                        (
                            state.isCameraReady &&
                                state.meteredEv100 != null
                            ),
                    environmentText = environmentLightText(state.evaluation),
                    onCameraClick = { selector = PreviewSelector.CAMERA },
                    onCaptureClick = if (state.isFrozen) onResumeLive else onFreezePreview,
                    onFilmClick = { selector = PreviewSelector.FILM },
                    onReturnClick = onResumeLive,
                    onDownloadClick = ::requestPhotoSave,
                    compact = compact,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(controlPanelHeight),
                )
            }
        }

        selector?.let { activeSelector ->
            PresetSelectorSheet(
                selector = activeSelector,
                presets = state.presets,
                selectedPreset = preset,
                onDismiss = { selector = null },
                onPresetSelected = { selected ->
                    onPresetSelected(selected.id)
                    selector = null
                },
                onFilmSelected = { film ->
                    onFilmSelected(film.id)
                    selector = null
                },
            )
        }
    }

    if (showsSettings) {
        PresetSettingsDialog(
            manualConfig = state.manualConfig,
            selectedPresetId = preset.id,
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
private fun PreviewControlPanel(
    preset: DisposableCameraPreset,
    cameraImageResource: Int,
    filmImageResource: Int,
    isFrozen: Boolean,
    isResultReady: Boolean,
    isSavingPhoto: Boolean,
    captureEnabled: Boolean,
    environmentText: String,
    onCameraClick: () -> Unit,
    onCaptureClick: () -> Unit,
    onFilmClick: () -> Unit,
    onReturnClick: () -> Unit,
    onDownloadClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = PREVIEW_PANEL_COLOR,
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
            if (isFrozen && isResultReady) {
                PreviewResultActions(
                    isSavingPhoto = isSavingPhoto,
                    onReturnClick = onReturnClick,
                    onDownloadClick = onDownloadClick,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PreviewSelectionTile(
                        imageResource = cameraImageResource,
                        contentDescription = "选择机型：${preset.controlLabel()}",
                        enabled = !isFrozen,
                        onClick = onCameraClick,
                    )
                    PreviewFreezeButton(
                        isFrozen = isFrozen,
                        enabled = captureEnabled,
                        onClick = onCaptureClick,
                    )
                    PreviewSelectionTile(
                        imageResource = filmImageResource,
                        contentDescription = "选择底片：${preset.film.controlLabel()}",
                        enabled = !isFrozen,
                        onClick = onFilmClick,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            EnvironmentLightPill(text = environmentText)
        }
    }
}

@Composable
private fun PreviewSelectionTile(
    imageResource: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(72.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        color = PREVIEW_SELECTOR_COLOR,
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
private fun PreviewResultActions(
    isSavingPhoto: Boolean,
    onReturnClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewFreezeButton(
            isFrozen = true,
            enabled = !isSavingPhoto,
            onClick = onReturnClick,
        )
        Surface(
            color = PREVIEW_SHUTTER_RED,
            shape = CircleShape,
            modifier = Modifier
                .size(CONTROL_BUTTON_SIZE)
                .alpha(if (isSavingPhoto) 0.6f else 1f)
                .clickable(enabled = !isSavingPhoto, onClick = onDownloadClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isSavingPhoto) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "下载模拟成片",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvironmentLightPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(38.dp),
        color = PREVIEW_LIGHT_STATUS_COLOR,
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
                tint = PREVIEW_SUN_COLOR,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                color = PREVIEW_TEXT_COLOR,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
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
        PreviewToolbarAction(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "返回",
            onClick = onBack,
        )
        PreviewToolbarAction(
            icon = Icons.Outlined.Settings,
            label = "设置",
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun PreviewToolbarAction(
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
            color = PREVIEW_SELECTOR_COLOR,
            shape = CircleShape,
            border = BorderStroke(2.dp, PREVIEW_SHUTTER_RED),
            modifier = modifier
                .size(CONTROL_BUTTON_SIZE)
                .alpha(if (enabled) 1f else 0.6f)
                .clickable(enabled = enabled, onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "退出冻结，恢复取景",
                    tint = PREVIEW_TOOLBAR_GREEN,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(CONTROL_BUTTON_SIZE)
                .alpha(if (enabled) 1f else 0.45f)
                .background(
                    color = PREVIEW_SHUTTER_RED,
                    shape = CircleShape,
                )
                .clip(CircleShape)
                .clickable(enabled = enabled && !isCaptureFeedbackVisible) {
                    isCaptureFeedbackVisible = true
                },
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(
                        color = Color.White,
                        shape = CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .scale(innerRingScale)
                    .background(
                        color = PREVIEW_SHUTTER_RED,
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
    selectedPresetId: String,
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
                        selectedPresetId,
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

private fun DisposableCameraPreset.controlLabel(): String {
    return when (id) {
        ManualCameraConfig.MANUAL_PRESET_ID -> "自定义机型"
        "kodak-power-flash-800" -> "FunSaver 800"
        "fujifilm-quicksnap-flash-400" -> "QuickSnap 400"
        "fujifilm-c400-jelly" -> "Fujifilm C400"
        "kodak-ec35-reusable" -> "Kodak EC35"
        else -> displayName
    }
}

private fun FilmProfile.controlLabel(): String {
    return when (id) {
        "generic-color-100" -> "ISO 100 彩负"
        "kodak-gold-200" -> "Kodak Gold 200"
        "kodak-ultra-max-400" -> "UltraMax 400"
        "generic-color-800" -> "ISO 800 彩负"
        "kodak-disposable-800" -> "Kodak 800"
        "fuji-superia-xtra-400" -> "Superia 400"
        "fuji-c400-400" -> "Fujifilm C400"
        else -> name
    }
}

private fun environmentLightText(evaluation: FilmPreviewEvaluation?): String {
    return when (evaluation?.rating) {
        PreviewSceneRating.GOOD -> "当前光线：适宜"
        PreviewSceneRating.CAUTION -> if ((evaluation.sceneDeltaEv ?: 0.0) < 0.0) {
            "当前光线：偏暗"
        } else {
            "当前光线：偏亮"
        }

        PreviewSceneRating.POOR -> if ((evaluation.sceneDeltaEv ?: 0.0) < 0.0) {
            "当前光线：过暗"
        } else {
            "当前光线：过亮"
        }

        PreviewSceneRating.UNAVAILABLE,
        null,
        -> "当前光线：分析中"
    }
}

private const val PREVIEW_ASPECT_RATIO = 2f / 3f
private const val PREVIEW_ZOOM_TOLERANCE = 0.02f
private const val COMPARISON_MIN_SPLIT = 0f
private const val COMPARISON_MAX_SPLIT = 1f
private val CONTROL_BUTTON_SIZE = 88.dp
private const val CAPTURE_FEEDBACK_DURATION_MS = 140L
private val PREVIEW_TOOLBAR_GREEN = Color(0xFF10372C)
private val PREVIEW_PANEL_COLOR = Color(0xFFFBFCF9)
private val PREVIEW_SELECTOR_COLOR = Color(0xFFF0F2EF)
private val PREVIEW_LIGHT_STATUS_COLOR = Color(0xFFF8F3E7)
private val PREVIEW_TEXT_COLOR = Color(0xFF17251F)
private val PREVIEW_MUTED_TEXT_COLOR = Color(0xFF69756F)
private val PREVIEW_SUN_COLOR = Color(0xFFD3A52B)
private val PREVIEW_SHUTTER_RED = Color(0xFFE45649)

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
