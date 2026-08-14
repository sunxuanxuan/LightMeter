package com.lightmeter.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lightmeter.app.camera.CameraOptics
import com.lightmeter.app.camera.CameraZoomState
import com.lightmeter.app.metering.GrayCardCalibration
import com.lightmeter.app.metering.GrayCardCalibrationEstimate
import com.lightmeter.app.metering.GrayCardCalibrationResult
import com.lightmeter.app.metering.MeteringConfig
import com.lightmeter.app.metering.MeteringMode
import com.lightmeter.app.metering.NormalizedMeteringRect
import kotlinx.coroutines.launch
import java.util.Locale

private val GRAY_CARD_REGION = NormalizedMeteringRect(
    left = 0.2,
    top = 0.2,
    right = 0.8,
    bottom = 0.8,
)

@Composable
fun GrayCardCalibrationScreen(
    onSave: (Double) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var referenceText by remember { mutableStateOf("10.0") }
    var liveEv100 by remember { mutableStateOf<Double?>(null) }
    var isCollecting by remember { mutableStateOf(false) }
    var warmupRemaining by remember {
        mutableIntStateOf(GrayCardCalibration.WARMUP_SAMPLE_COUNT)
    }
    val samples = remember { mutableStateListOf<Double>() }
    var estimate by remember { mutableStateOf<GrayCardCalibrationEstimate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "灰卡校验",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "将 18% 灰卡均匀照亮并填满中央框。用可靠测光表对同一灰卡测量，" +
                    "设置 ISO 100、曝光补偿 0，然后输入参考 EV100。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (hasPermission) {
                    CameraPreviewView(
                        meteringConfig = MeteringConfig(
                            mode = MeteringMode.AVERAGE,
                            viewfinderRect = GRAY_CARD_REGION,
                            previewAspectRatio = 4.0 / 3.0,
                            calibrationOffset = 0.0,
                            revision = CALIBRATION_REVISION,
                        ),
                        targetZoomRatio = 1f,
                        freezeRequestId = 0,
                        shouldCaptureFrame = false,
                        onMeteringResult = { result ->
                            scope.launch {
                                liveEv100 = result.ev100
                                if (!isCollecting) return@launch
                                if (warmupRemaining > 0) {
                                    warmupRemaining--
                                    return@launch
                                }
                                samples += result.ev100
                                if (samples.size >= GrayCardCalibration.REQUIRED_SAMPLE_COUNT) {
                                    isCollecting = false
                                    when (
                                        val resultEstimate = GrayCardCalibration.estimate(
                                            referenceEv100 = referenceText.toReferenceEv()
                                                ?: Double.NaN,
                                            measuredEv100Samples = samples,
                                        )
                                    ) {
                                        is GrayCardCalibrationResult.Success -> {
                                            estimate = resultEstimate.estimate
                                            message = null
                                        }

                                        is GrayCardCalibrationResult.Failure -> {
                                            estimate = null
                                            message = resultEstimate.reason
                                        }
                                    }
                                }
                            }
                        },
                        onFrameCaptured = {},
                        onOpticsAvailable = { _: CameraOptics -> },
                        onZoomStateChanged = { _: CameraZoomState -> },
                        onReady = { cameraError = null },
                        onError = {
                            cameraError = it.message ?: "相机初始化失败"
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    CameraViewfinderMask(
                        viewfinder = GRAY_CARD_REGION,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                    ) {
                        Text("允许相机权限")
                    }
                }
            }

            OutlinedTextField(
                value = referenceText,
                onValueChange = {
                    referenceText = it
                    estimate = null
                    message = null
                },
                label = { Text("参考 EV100") },
                supportingText = {
                    Text("来自专业测光表或已校准相机，范围 -6.0 到 24.0")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !isCollecting,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "手机当前原始测光：${
                    liveEv100?.let { String.format(Locale.US, "%.1f EV", it) } ?: "--"
                }",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (isCollecting) {
                val progress = if (warmupRemaining > 0) {
                    0f
                } else {
                    samples.size.toFloat() / GrayCardCalibration.REQUIRED_SAMPLE_COUNT
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (warmupRemaining > 0) {
                        "等待自动曝光稳定…"
                    } else {
                        "正在采集 ${samples.size}/${GrayCardCalibration.REQUIRED_SAMPLE_COUNT}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            estimate?.let {
                Text(
                    text = "校验完成：偏移 ${
                        String.format(Locale.US, "%+.2f EV", it.offset)
                    }，采样波动 ${String.format(Locale.US, "%.2f EV", it.spreadStops)}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            (message ?: cameraError)?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                if (estimate == null) {
                    Button(
                        onClick = {
                            if (referenceText.toReferenceEv() == null) {
                                message = "请输入有效的参考 EV100"
                            } else {
                                samples.clear()
                                estimate = null
                                message = null
                                warmupRemaining = GrayCardCalibration.WARMUP_SAMPLE_COUNT
                                isCollecting = true
                            }
                        },
                        enabled = hasPermission && !isCollecting && liveEv100 != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isCollecting) "采集中" else "开始采集")
                    }
                } else {
                    Button(
                        onClick = { onSave(requireNotNull(estimate).offset) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存校验")
                    }
                }
            }
        }
    }
}

private fun String.toReferenceEv(): Double? {
    return replace(',', '.').toDoubleOrNull()?.takeIf { it in -6.0..24.0 }
}

private const val CALIBRATION_REVISION = 0x47524159L
