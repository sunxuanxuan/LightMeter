package com.lightmeter.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import com.lightmeter.app.metering.MeteringAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

@SuppressLint("UnsafeOptInUsageError")
class CameraController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var requestedZoomRatio = 1.0f
    private var lastAppliedZoomRatio = Float.NaN
    private var released = false

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: MeteringAnalyzer,
        onOpticsAvailable: (CameraOptics) -> Unit,
        onZoomStateChanged: (CameraZoomState) -> Unit,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener(
            {
                if (released) return@addListener

                val provider = runCatching(providerFuture::get)
                    .getOrElse {
                        onError(it)
                        return@addListener
                    }
                previewView.doOnLayout {
                    if (released) return@doOnLayout

                    runCatching {
                        val viewPort = previewView.viewPort
                            ?: error("Preview ViewPort is unavailable after layout")
                        val preview = Preview.Builder()
                            .build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysisBuilder = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        Camera2Interop.Extender(analysisBuilder)
                            .setSessionCaptureCallback(analyzer)
                        val analysis = analysisBuilder
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }
                        val useCaseGroup = UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .build()

                        provider.unbindAll()
                        val boundCamera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            useCaseGroup,
                        )
                        camera = boundCamera
                        boundCamera.cameraInfo.zoomState.observe(lifecycleOwner) { zoomState ->
                            onZoomStateChanged(
                                CameraZoomState(
                                    zoomRatio = zoomState.zoomRatio,
                                    minZoomRatio = zoomState.minZoomRatio,
                                    maxZoomRatio = zoomState.maxZoomRatio,
                                    isInitialized = true,
                                ),
                            )
                        }
                        applyRequestedZoom()

                        val cameraInfo = Camera2CameraInfo.from(boundCamera.cameraInfo)
                        val fallbackAperture = cameraInfo
                            .getCameraCharacteristic(
                                CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES,
                            )
                            ?.firstOrNull()
                            ?.toDouble()
                        analyzer.updateFallbackAperture(fallbackAperture)
                        val sensorSize = cameraInfo.getCameraCharacteristic(
                            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
                        )
                        val focalLength = cameraInfo.getCameraCharacteristic(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                        )
                            ?.firstOrNull()
                            ?.toDouble()
                        if (sensorSize != null && focalLength != null && focalLength > 0.0) {
                            onOpticsAvailable(
                                CameraOptics(
                                    sensorWidthMm = sensorSize.width.toDouble(),
                                    sensorHeightMm = sensorSize.height.toDouble(),
                                    focalLengthMm = focalLength,
                                ),
                            )
                        }
                        cameraProvider = provider
                    }.onSuccess {
                        onReady()
                    }.onFailure(onError)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun setZoomRatio(zoomRatio: Float) {
        requestedZoomRatio = zoomRatio.coerceAtLeast(0.1f)
        applyRequestedZoom()
    }

    private fun applyRequestedZoom() {
        val currentCamera = camera ?: return
        val zoomState = currentCamera.cameraInfo.zoomState.value ?: return
        val target = requestedZoomRatio.coerceIn(
            zoomState.minZoomRatio,
            zoomState.maxZoomRatio,
        )
        if (lastAppliedZoomRatio.isFinite() && abs(lastAppliedZoomRatio - target) < 0.001f) {
            return
        }
        lastAppliedZoomRatio = target
        currentCamera.cameraControl.setZoomRatio(target)
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        lastAppliedZoomRatio = Float.NaN
    }

    fun release() {
        released = true
        unbind()
        analysisExecutor.shutdown()
    }
}
