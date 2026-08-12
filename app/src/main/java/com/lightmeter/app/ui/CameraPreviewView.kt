package com.lightmeter.app.ui

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lightmeter.app.camera.CameraController
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.metering.MeteringAnalyzer
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringResult

@Composable
fun CameraPreviewView(
    meteringConfig: MeteringConfig,
    freezeRequestId: Int,
    shouldCaptureFrame: Boolean,
    onMeteringResult: (MeteringResult) -> Unit,
    onFrameCaptured: (Bitmap?) -> Unit,
    onOpticsAvailable: (CameraOptics) -> Unit,
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
    }

    LaunchedEffect(freezeRequestId, shouldCaptureFrame) {
        if (shouldCaptureFrame && freezeRequestId > 0) {
            currentOnFrameCaptured.value(previewView.bitmap)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        cameraController.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            analyzer = analyzer,
            onOpticsAvailable = onOpticsAvailable,
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
