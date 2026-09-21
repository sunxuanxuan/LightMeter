package com.lightmeter.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LiveData
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.lightmeter.app.BuildConfig
import com.lightmeter.app.metering.ImageProxyBitmapConverter
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
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
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
        enableHighResolutionCapture: Boolean = false,
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
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            ANALYSIS_PREFERRED_SIZE,
                                            ResolutionStrategy
                                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                        ),
                                    )
                                    .build(),
                            )
                        Camera2Interop.Extender(analysisBuilder)
                            .setSessionCaptureCallback(analyzer)
                        val analysis = analysisBuilder
                            .build()
                            .also { it.setAnalyzer(analysisExecutor, analyzer) }
                        val highResolutionCapture = if (enableHighResolutionCapture) {
                            ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .setTargetRotation(previewView.display.rotation)
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY,
                                        )
                                        .build(),
                                )
                                .build()
                        } else {
                            null
                        }
                        fun buildUseCaseGroup(capture: ImageCapture?): UseCaseGroup {
                            val builder = UseCaseGroup.Builder()
                                .setViewPort(viewPort)
                                .addUseCase(preview)
                                .addUseCase(analysis)
                            capture?.let(builder::addUseCase)
                            return builder.build()
                        }

                        provider.unbindAll()
                        var activeImageCapture = highResolutionCapture
                        val boundCamera = runCatching {
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                buildUseCaseGroup(highResolutionCapture),
                            )
                        }.getOrElse { captureError ->
                            if (highResolutionCapture == null) throw captureError
                            if (BuildConfig.DEBUG) {
                                Log.w(
                                    TAG,
                                    "ImageCapture binding failed; using analysis-frame fallback",
                                    captureError,
                                )
                            }
                            provider.unbindAll()
                            activeImageCapture = null
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                buildUseCaseGroup(null),
                            )
                        }
                        camera = boundCamera
                        imageCapture = activeImageCapture
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

    fun captureHighResolution(
        onCaptured: (Bitmap) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("High-resolution capture is unavailable"))
            return
        }
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = ImageProxyBitmapConverter.convert(image)
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                TAG,
                                "highResolutionCapture image=%dx%d crop=%s output=%dx%d".format(
                                    image.width,
                                    image.height,
                                    image.cropRect.toShortString(),
                                    bitmap.width,
                                    bitmap.height,
                                ),
                            )
                        }
                        onCaptured(bitmap)
                    } catch (error: Throwable) {
                        onError(error)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            },
        )
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
        imageCapture = null
        lastAppliedZoomRatio = Float.NaN
    }

    fun release() {
        released = true
        unbind()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CameraController"
        val ANALYSIS_PREFERRED_SIZE = Size(1920, 1440)
    }
}
