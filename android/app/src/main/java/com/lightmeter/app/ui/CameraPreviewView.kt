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
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.camera.CameraZoomState
import com.lightmeter.app.metering.CapturedExposureFrame
import com.lightmeter.app.metering.MeteringAnalyzer
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringResult
import com.lightmeter.app.metering.NormalizedMeteringRect
import kotlinx.coroutines.delay

@Composable
fun CameraPreviewView(
    meteringConfig: MeteringConfig,
    targetZoomRatio: Float,
    freezeRequestId: Int,
    shouldCaptureFrame: Boolean,
    onMeteringResult: (MeteringResult) -> Unit,
    onFrameCaptured: (CapturedExposureFrame?) -> Unit,
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
            analyzer.requestFrameCapture(freezeRequestId)
            val capturedFrame = try {
                awaitCapturedFrame(
                    analyzer = analyzer,
                    requestId = freezeRequestId,
                )
            } finally {
                analyzer.cancelFrameCapture(freezeRequestId)
            }
            if (capturedFrame == null) {
                currentOnFrameCaptured.value(null)
                return@LaunchedEffect
            }
            currentOnFrameCaptured.value(capturedFrame)
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
): CapturedExposureFrame? {
    val deadlineNs = System.nanoTime() + FRAME_CAPTURE_TIMEOUT_NS
    while (System.nanoTime() < deadlineNs) {
        analyzer.takeCapturedFrame(requestId)?.let { return it }
        delay(FRAME_CAPTURE_POLL_INTERVAL_MS)
    }
    return null
}
private const val FRAME_CAPTURE_TIMEOUT_NS = 1_500_000_000L
private const val FRAME_CAPTURE_POLL_INTERVAL_MS = 10L
