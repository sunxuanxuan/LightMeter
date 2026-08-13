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
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.lightmeter.app.metering.MeteringAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.roundToInt

data class CameraExposureBracket(
    val originalCompensationIndex: Int,
    val minimumCompensationIndex: Int,
    val maximumCompensationIndex: Int,
    val compensationStepStops: Double,
)

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
    private var observedZoomState: LiveData<ZoomState>? = null
    private var zoomStateObserver: Observer<ZoomState>? = null
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
                        clearZoomStateObserver()
                        val zoomStateSource = boundCamera.cameraInfo.zoomState
                        val observer = Observer<ZoomState> { zoomState ->
                            onZoomStateChanged(
                                CameraZoomState(
                                    zoomRatio = zoomState.zoomRatio,
                                    minZoomRatio = zoomState.minZoomRatio,
                                    maxZoomRatio = zoomState.maxZoomRatio,
                                    isInitialized = true,
                                ),
                            )
                        }
                        observedZoomState = zoomStateSource
                        zoomStateObserver = observer
                        zoomStateSource.observe(lifecycleOwner, observer)
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

    fun createExposureBracket(): CameraExposureBracket? {
        val currentCamera = camera ?: return null
        val exposureState = currentCamera.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return null

        val stepStops = exposureState.exposureCompensationStep.toDouble()
        if (!stepStops.isFinite() || stepStops <= 0.0) return null
        return CameraExposureBracket(
            originalCompensationIndex = exposureState.exposureCompensationIndex,
            minimumCompensationIndex = exposureState.exposureCompensationRange.lower,
            maximumCompensationIndex = exposureState.exposureCompensationRange.upper,
            compensationStepStops = stepStops,
        )
    }

    suspend fun applyExposureProbe(
        bracket: CameraExposureBracket,
        requestedOffsetStops: Double,
    ): Double? {
        require(requestedOffsetStops.isFinite() && requestedOffsetStops != 0.0)
        val currentCamera = camera ?: return null
        val requestedStepCount = (
            requestedOffsetStops / bracket.compensationStepStops
        ).roundToInt()
        val targetIndex = (
            bracket.originalCompensationIndex + requestedStepCount
        ).coerceIn(
            bracket.minimumCompensationIndex,
            bracket.maximumCompensationIndex,
        )
        if (targetIndex == bracket.originalCompensationIndex) return null
        if (!applyExposureCompensationIndex(currentCamera, targetIndex)) return null
        return (targetIndex - bracket.originalCompensationIndex) *
            bracket.compensationStepStops
    }

    suspend fun restoreExposureBracket(bracket: CameraExposureBracket) {
        val currentCamera = camera ?: return
        applyExposureCompensationIndex(currentCamera, bracket.originalCompensationIndex)
    }

    private suspend fun applyExposureCompensationIndex(
        currentCamera: Camera,
        index: Int,
    ): Boolean {
        val future = currentCamera.cameraControl.setExposureCompensationIndex(index)
        return suspendCoroutine { continuation ->
            future.addListener(
                {
                    continuation.resume(runCatching(future::get).isSuccess)
                },
                ContextCompat.getMainExecutor(appContext),
            )
        }
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

    private fun clearZoomStateObserver() {
        val observer = zoomStateObserver
        if (observer != null) {
            observedZoomState?.removeObserver(observer)
        }
        observedZoomState = null
        zoomStateObserver = null
    }

    fun unbind() {
        clearZoomStateObserver()
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
