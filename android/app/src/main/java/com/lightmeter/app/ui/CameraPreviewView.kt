package com.lightmeter.app.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lightmeter.app.camera.CameraController
import com.lightmeter.app.camera.CameraExposureBracket
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.camera.CameraZoomState
import com.lightmeter.app.metering.CapturedExposureFrame
import com.lightmeter.app.metering.ExposureRiskCalculator
import com.lightmeter.app.metering.ExposureSnapshot
import com.lightmeter.app.metering.MeteringAnalyzer
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringResult
import com.lightmeter.app.metering.NormalizedMeteringRect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max

@Composable
fun CameraPreviewView(
    meteringConfig: MeteringConfig,
    targetZoomRatio: Float,
    freezeRequestId: Int,
    shouldCaptureFrame: Boolean,
    exposureCompensation: Double,
    highlightLatitudeStops: Double,
    shadowLatitudeStops: Double,
    riskReferenceEv100: Double? = null,
    onMeteringResult: (MeteringResult) -> Unit,
    onFrameCaptured: (CapturedExposureFrame?) -> Unit,
    onDetailProbesCaptured: (ExposureSnapshot?, ExposureSnapshot?) -> Unit,
    onOpticsAvailable: (CameraOptics) -> Unit,
    onZoomStateChanged: (CameraZoomState) -> Unit,
    onReady: () -> Unit,
    onError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnMeteringResult = rememberUpdatedState(onMeteringResult)
    val currentOnFrameCaptured = rememberUpdatedState(onFrameCaptured)
    val currentOnDetailProbesCaptured = rememberUpdatedState(onDetailProbesCaptured)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analyzer = remember {
        MeteringAnalyzer(meteringConfig) { result ->
            currentOnMeteringResult.value(result)
        }
    }
    val cameraController = remember { CameraController(context) }

    SideEffect {
        analyzer.updateConfig(meteringConfig)
        cameraController.setZoomRatio(targetZoomRatio)
    }

    LaunchedEffect(freezeRequestId, shouldCaptureFrame) {
        if (shouldCaptureFrame && freezeRequestId > 0) {
            val processingDeadlineNs = System.nanoTime() + FREEZE_PROCESSING_TIMEOUT_NS
            analyzer.requestFrameCapture(freezeRequestId)
            val capturedFrame = try {
                awaitCapturedFrame(
                    analyzer = analyzer,
                    requestId = freezeRequestId,
                    processingDeadlineNs = processingDeadlineNs,
                )
            } finally {
                analyzer.cancelFrameCapture(freezeRequestId)
            }
            if (capturedFrame == null) {
                currentOnFrameCaptured.value(null)
                currentOnDetailProbesCaptured.value(null, null)
                return@LaunchedEffect
            }
            val baselineSnapshot = capturedFrame.snapshot
            currentOnFrameCaptured.value(capturedFrame)

            val referenceEv100 = riskReferenceEv100
                ?: ExposureRiskCalculator.referenceEv100(
                    frozenMeteredEv100 = baselineSnapshot.meteredEv100,
                    exposureCompensation = exposureCompensation,
                )
            val requirements = ExposureRiskCalculator.probeRequirements(
                exposureMap = baselineSnapshot.exposureMap,
                viewfinder = meteringConfig.viewfinderRect,
                referenceEv100 = referenceEv100,
                highlightLatitudeStops = highlightLatitudeStops,
                shadowLatitudeStops = shadowLatitudeStops,
            )
            if (requirements.isEmpty) {
                currentOnDetailProbesCaptured.value(null, null)
                return@LaunchedEffect
            }

            val bracket = cameraController.createExposureBracket()
            if (bracket == null) {
                currentOnDetailProbesCaptured.value(null, null)
                return@LaunchedEffect
            }

            var lastProbeTimestampNs = baselineSnapshot.timestampNs
            var shadowProbeSnapshot: ExposureSnapshot? = null
            var highlightProbeSnapshot: ExposureSnapshot? = null
            try {
                if (requirements.shadow) {
                    shadowProbeSnapshot = captureProbeSnapshot(
                        cameraController = cameraController,
                        analyzer = analyzer,
                        bracket = bracket,
                        baseline = baselineSnapshot,
                        afterTimestampNs = lastProbeTimestampNs,
                        requestedOffsetStops = SHADOW_PROBE_STOPS,
                        processingDeadlineNs = processingDeadlineNs,
                    )
                    shadowProbeSnapshot?.let {
                        lastProbeTimestampNs = it.timestampNs
                    }
                }
                if (requirements.highlight) {
                    highlightProbeSnapshot = captureProbeSnapshot(
                        cameraController = cameraController,
                        analyzer = analyzer,
                        bracket = bracket,
                        baseline = baselineSnapshot,
                        afterTimestampNs = lastProbeTimestampNs,
                        requestedOffsetStops = HIGHLIGHT_PROBE_STOPS,
                        processingDeadlineNs = processingDeadlineNs,
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    cameraController.restoreExposureBracket(bracket)
                }
            }
            currentOnDetailProbesCaptured.value(
                shadowProbeSnapshot,
                highlightProbeSnapshot,
            )
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        cameraController.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            analyzer = analyzer,
            onOpticsAvailable = onOpticsAvailable,
            onZoomStateChanged = onZoomStateChanged,
            onReady = onReady,
            onError = onError,
        )
        onDispose {
            cameraController.release()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
fun CameraViewfinderMask(
    viewfinder: NormalizedMeteringRect,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val left = (viewfinder.left * size.width).toFloat()
        val top = (viewfinder.top * size.height).toFloat()
        val right = (viewfinder.right * size.width).toFloat()
        val bottom = (viewfinder.bottom * size.height).toFloat()
        val maskColor = Color.Black.copy(alpha = 0.52f)

        drawRect(maskColor, size = Size(size.width, top))
        drawRect(
            maskColor,
            topLeft = Offset(0f, bottom),
            size = Size(size.width, size.height - bottom),
        )
        drawRect(
            maskColor,
            topLeft = Offset(0f, top),
            size = Size(left, bottom - top),
        )
        drawRect(
            maskColor,
            topLeft = Offset(right, top),
            size = Size(size.width - right, bottom - top),
        )
        drawRect(
            color = Color.White.copy(alpha = 0.78f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private suspend fun awaitCapturedFrame(
    analyzer: MeteringAnalyzer,
    requestId: Int,
    processingDeadlineNs: Long,
): CapturedExposureFrame? {
    val deadlineNs = minOf(
        processingDeadlineNs,
        System.nanoTime() + FRAME_CAPTURE_TIMEOUT_NS,
    )
    while (System.nanoTime() < deadlineNs) {
        analyzer.takeCapturedFrame(requestId)?.let { return it }
        delay(FRAME_CAPTURE_POLL_INTERVAL_MS)
    }
    return null
}

private suspend fun captureProbeSnapshot(
    cameraController: CameraController,
    analyzer: MeteringAnalyzer,
    bracket: CameraExposureBracket,
    baseline: ExposureSnapshot,
    afterTimestampNs: Long,
    requestedOffsetStops: Double,
    processingDeadlineNs: Long,
): ExposureSnapshot? {
    val remainingMs = (
        (processingDeadlineNs - System.nanoTime()) / 1_000_000
        ).coerceAtLeast(0)
    if (remainingMs == 0L) return null
    val actualOffsetStops = withTimeoutOrNull(remainingMs) {
        cameraController.applyExposureProbe(
            bracket = bracket,
            requestedOffsetStops = requestedOffsetStops,
        )
    } ?: return null
    if (abs(actualOffsetStops) < MIN_USABLE_PROBE_STOPS) return null
    return awaitProbeSnapshot(
        analyzer = analyzer,
        baseline = baseline,
        afterTimestampNs = afterTimestampNs,
        exposureOffsetStops = actualOffsetStops,
        processingDeadlineNs = processingDeadlineNs,
    )
}

private suspend fun awaitProbeSnapshot(
    analyzer: MeteringAnalyzer,
    baseline: ExposureSnapshot,
    afterTimestampNs: Long,
    exposureOffsetStops: Double,
    processingDeadlineNs: Long,
): ExposureSnapshot? {
    val deadlineNs = minOf(
        processingDeadlineNs,
        System.nanoTime() + EXPOSURE_PROBE_TIMEOUT_NS,
    )
    val minimumSettingChange = max(
        MIN_PROBE_SETTING_CHANGE_STOPS,
        abs(exposureOffsetStops) * MIN_PROBE_SETTING_CHANGE_RATIO,
    )
    while (System.nanoTime() < deadlineNs) {
        delay(SHADOW_PROBE_POLL_INTERVAL_MS)
        val candidate = analyzer.latestExposureSnapshot() ?: continue
        if (candidate.timestampNs <= afterTimestampNs) continue

        val baselineSetting = baseline.exposureMap.cameraSettingEv100
        val candidateSetting = candidate.exposureMap.cameraSettingEv100
        val observedOffsetStops = baselineSetting - candidateSetting
        if (
            baselineSetting.isFinite() &&
            candidateSetting.isFinite() &&
            (
                exposureOffsetStops > 0.0 &&
                    observedOffsetStops >= minimumSettingChange ||
                    exposureOffsetStops < 0.0 &&
                    observedOffsetStops <= -minimumSettingChange
                )
        ) {
            return candidate
        }
    }
    return null
}

private const val SHADOW_PROBE_STOPS = 2.0
private const val HIGHLIGHT_PROBE_STOPS = -2.0
private const val FREEZE_PROCESSING_TIMEOUT_NS = 1_350_000_000L
private const val FRAME_CAPTURE_TIMEOUT_NS = 600_000_000L
private const val FRAME_CAPTURE_POLL_INTERVAL_MS = 10L
private const val EXPOSURE_PROBE_TIMEOUT_NS = 800_000_000L
private const val SHADOW_PROBE_POLL_INTERVAL_MS = 50L
private const val MIN_USABLE_PROBE_STOPS = 1.0
private const val MIN_PROBE_SETTING_CHANGE_STOPS = 0.2
private const val MIN_PROBE_SETTING_CHANGE_RATIO = 0.5
