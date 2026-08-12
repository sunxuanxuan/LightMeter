package com.lightmeter.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.lightmeter.app.metering.MeteringAnalyzer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var released = false

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: MeteringAnalyzer,
        onOpticsAvailable: (CameraOptics) -> Unit,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener(
            {
                if (released) return@addListener

                runCatching {
                    val provider = providerFuture.get()
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

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    val cameraInfo = Camera2CameraInfo.from(camera.cameraInfo)
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
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    fun release() {
        released = true
        unbind()
        analysisExecutor.shutdown()
    }
}
