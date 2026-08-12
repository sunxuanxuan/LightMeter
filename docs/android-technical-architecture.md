# Android 技术架构设计

## 1. 目标

本文档定义 Android 胶片测光 App V1 的内部技术架构。

这里的“后端架构”指 App 内部底层能力，不包含远端服务。V1 的核心职责包括：

- 实时相机预览。
- YUV 图像分析。
- Camera2 曝光元数据读取。
- 相机权限处理。
- 测光算法调度。
- 曝光组合计算调度。
- 取景框和点击测光区域状态管理。

推荐技术栈：

- Kotlin
- Jetpack Compose
- CameraX
- Camera2 Interop
- ViewModel
- Kotlin Coroutines / Flow

## 2. 总体架构

V1 建议采用分层架构：

```text
UI Layer
  └── Compose Screen
      ├── Camera Preview
      ├── 取景框 Overlay
      ├── 点击测光 Overlay
      └── 参数面板 ISO / EC / 曝光组合

Presentation Layer
  └── MeteringViewModel
      ├── UI State
      ├── 用户操作处理
      └── 调度测光与曝光计算

Camera Layer
  ├── CameraController
  ├── Preview UseCase
  ├── ImageAnalysis UseCase
  └── Camera2 Metadata Reader

Domain Layer
  ├── MeteringAnalyzer
  ├── EvCalculator
  ├── ExposurePairGenerator
  └── PrimaryRecommendationSelector

Data / Config Layer
  ├── 用户设置 ISO / EC / 校准偏移
  ├── 档位表
  └── 设备能力信息
```

核心原则：

```text
camera 只负责拿画面和元数据
metering 只负责把画面转成 EV
exposure 只负责把 EV 转成曝光组合
ui 只负责展示和用户输入
```

## 3. 相机预览

相机预览使用 CameraX `Preview`。

核心职责：

```text
1. 请求后置摄像头。
2. 绑定 Preview UseCase。
3. 将预览输出到 PreviewView。
4. Compose 中通过 AndroidView 承载 PreviewView。
```

V1 固定使用后置主摄：

```kotlin
CameraSelector.DEFAULT_BACK_CAMERA
```

第一版不建议支持多摄切换。不同摄像头的视角、焦距、光圈、曝光策略和 ISP 行为不同，会影响测光一致性。

Compose 层结构建议：

```text
MeteringScreen
  ├── CameraPreviewView
  ├── ViewfinderOverlay
  ├── SpotMeteringOverlay
  └── ExposurePanel
```

预览层只负责显示实时画面，取景框和测光圆形区域由 Compose Overlay 绘制，不参与相机图像渲染。

## 4. 取景框 Overlay

V1 支持以下画幅和焦段组合：

```text
135 + 35mm
135 + 50mm
135 + 75mm
6x4.5 + 75mm
```

取景框是预览上的辅助框，不改变手机物理焦距。

建议抽象：

```kotlin
data class FramePreset(
    val format: FrameFormat,
    val focalLengthMm: Int
)

enum class FrameFormat {
    FILM_135,
    MEDIUM_FORMAT_645
}
```

Overlay 绘制规则：

```text
1. 根据当前 preset 计算取景框宽高比和裁剪范围。
2. 在 PreviewView 上方用 Compose Canvas 绘制边框。
3. 框外区域可以使用半透明遮罩，帮助用户理解实际取景范围。
```

V1 不需要在算法层按取景框裁剪测光区域。默认测光仍基于完整预览或点击测光 ROI。后续如果需要“按取景框测光”，可以将取景框区域作为平均测光 ROI。

## 5. 图像分析

图像分析使用 CameraX `ImageAnalysis` 获取 YUV 帧。

配置建议：

```text
ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
```

原因：

```text
测光只关心最新画面，不需要处理历史帧。
使用 KEEP_ONLY_LATEST 可以避免分析线程积压导致 UI 延迟。
```

目标分析频率：

```text
10Hz ~ 15Hz
```

即每 `66ms ~ 100ms` 处理一次。测光不需要每一帧都计算，节流可以降低功耗并提升数值稳定性。

图像分析流程：

```text
1. CameraX 输出 ImageProxy。
2. 判断是否需要节流跳过当前帧。
3. 读取 Y 平面。
4. 根据当前测光模式确定 ROI。
5. 计算 ROI 内 Y_linear 的 trimmed mean。
6. 结合 Camera2 元数据计算 EV100。
7. 对 EV100 做时间平滑。
8. 输出 MeteringResult。
9. 关闭 ImageProxy。
```

关键要求：

```text
ImageProxy.close() 必须在每帧处理结束后调用。
```

否则 CameraX 分析管线会卡住。

## 6. Camera2 元数据读取

EV 反推需要读取手机当前曝光参数：

```text
SENSOR_EXPOSURE_TIME
SENSOR_SENSITIVITY
LENS_APERTURE
```

CameraX 本身不稳定暴露完整 CaptureResult，建议使用 Camera2 Interop。

建议抽象：

```kotlin
interface ExposureMetadataProvider {
    fun latestMetadata(): CameraExposureMetadata?
}

data class CameraExposureMetadata(
    val exposureTimeNs: Long,
    val sensorSensitivity: Int,
    val aperture: Double,
    val timestampNs: Long?
)
```

图像分析线程使用最近一次 metadata。

V1 可以接受 `ImageProxy` 与 `CaptureResult` 不是严格同一帧，但需要容错：

```text
metadata 缺失时，不更新 EV。
exposureTime <= 0 时，丢弃当前帧。
sensorSensitivity <= 0 时，丢弃当前帧。
aperture 缺失时，尝试使用 CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES 的第一个值。
```

如果设备完全无法提供曝光元数据，则 UI 应提示当前设备无法进行可靠测光。

## 7. 权限处理

V1 需要相机权限：

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

权限由 UI / ViewModel 层控制，相机层只在权限已满足时启动。

权限状态建议建模为：

```kotlin
enum class CameraPermissionState {
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
    UNKNOWN
}
```

权限流程：

```text
1. 首次进入测光页面，检查 CAMERA 权限。
2. 未授权时展示权限说明和请求按钮。
3. 用户授权后启动 CameraX。
4. 用户拒绝后展示无法测光的空状态。
5. 用户永久拒绝后，引导进入系统设置。
```

不要让 `CameraController` 自己请求权限。这样可以保持相机层无 UI 依赖，也便于测试和状态管理。

## 8. 数据流设计

V1 使用单向数据流。

```text
User Input
  -> MeteringViewModel
  -> 更新 MeteringConfig
  -> Camera Analysis 读取最新配置
  -> 输出 MeteringResult
  -> ViewModel 合成 ExposureRecommendation
  -> UI State
  -> Compose 渲染
```

核心 UI State：

```kotlin
data class MeteringUiState(
    val permissionState: CameraPermissionState,
    val selectedIso: Int,
    val exposureCompensation: Double,
    val meteringMode: MeteringMode,
    val spotMeteringPoint: NormalizedPoint?,
    val framePreset: FramePreset,
    val ev100Metered: Double?,
    val evTarget: Double?,
    val primaryExposure: ExposurePair?,
    val equivalentExposures: List<ExposurePair>,
    val calibrationOffset: Double,
    val isCameraReady: Boolean,
    val errorMessage: String?
)
```

点击测光点建议使用归一化坐标：

```kotlin
data class NormalizedPoint(
    val x: Double, // 0.0 ~ 1.0
    val y: Double  // 0.0 ~ 1.0
)
```

使用归一化坐标的好处：

```text
1. UI 尺寸变化时不需要重算业务状态。
2. 横竖屏切换时更容易转换。
3. PreviewView 与 ImageAnalysis 坐标转换可以集中在一个 mapper 中处理。
```

## 9. 测光配置

Camera Analysis 需要读取当前测光配置。

建议定义：

```kotlin
data class MeteringConfig(
    val mode: MeteringMode,
    val spotPoint: NormalizedPoint?,
    val calibrationOffset: Double
)

enum class MeteringMode {
    AVERAGE,
    SPOT
}
```

配置更新来自 ViewModel。分析线程读取最新配置时需要避免锁竞争。

V1 可以使用：

```text
AtomicReference<MeteringConfig>
```

这样 ImageAnalysis 在后台线程读取配置时不会阻塞 UI。

## 10. 线程模型

建议线程拆分：

```text
Main Thread
  - Compose UI
  - ViewModel StateFlow 更新

Camera Executor
  - ImageAnalysis 帧处理
  - Y 平面采样
  - ROI 亮度计算

ViewModel Coroutine Scope
  - 合并 MeteringResult
  - 生成 ExposureRecommendation
  - 更新 UI State
```

`ImageAnalysis` 使用单线程 executor：

```kotlin
Executors.newSingleThreadExecutor()
```

性能要求：

```text
不要在主线程处理 YUV 像素。
不要每帧生成大量临时对象。
ROI 采样可以降采样，例如每 2 到 4 个像素采样一次。
测光结果输出频率控制在 10Hz 到 15Hz。
```

## 11. 坐标转换

点击测光需要把 Compose 点击坐标转换到 `ImageProxy` 图像坐标。

需要处理：

```text
1. UI 预览区域尺寸。
2. PreviewView scaleType。
3. 分析帧尺寸。
4. 图像旋转角度。
5. 前后摄像头镜像。
6. 预览裁剪或 letterbox。
```

V1 固定后置摄像头，因此可以先不处理前置镜像。

建议抽象：

```kotlin
interface PreviewCoordinateMapper {
    fun previewToImage(
        point: NormalizedPoint,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int
    ): ImagePoint
}

data class ImagePoint(
    val x: Int,
    val y: Int
)
```

坐标转换应集中实现，不要散落在 UI 和分析代码中。

## 12. 模块划分

建议包结构：

```text
camera/
  CameraController.kt
  CameraPreviewHost.kt
  ExposureMetadataProvider.kt
  PreviewCoordinateMapper.kt

metering/
  MeteringAnalyzer.kt
  MeteringMode.kt
  MeteringConfig.kt
  MeteringResult.kt
  EvCalculator.kt

exposure/
  ExposureStopTables.kt
  ExposurePairGenerator.kt
  PrimaryRecommendationSelector.kt
  ExposureRecommendation.kt

ui/
  MeteringScreen.kt
  CameraPreviewView.kt
  ViewfinderOverlay.kt
  SpotMeteringOverlay.kt
  ExposurePanel.kt

settings/
  UserPreferenceRepository.kt
```

各模块职责：

```text
camera:
  管理 CameraX 生命周期、Preview、ImageAnalysis、Camera2 metadata。

metering:
  从 YUV 帧和相机元数据计算 EV100。

exposure:
  根据 EV100、ISO、曝光补偿生成快门/光圈推荐。

ui:
  展示预览、参数面板、取景框、测光区域和结果。

settings:
  保存 ISO、曝光补偿、校准偏移、默认取景框等用户配置。
```

## 13. 异常与降级

V1 需要处理以下情况：

```text
无相机权限：
  不启动相机，展示权限说明。

无后置相机：
  展示设备不支持提示。

无法读取曝光元数据：
  不更新 EV，提示当前设备不支持可靠测光。

aperture 缺失：
  使用 CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES 兜底。

图像分析帧为空：
  跳过当前帧。

画面过暗或过亮：
  返回最接近曝光组合，并提示超出推荐范围。

分析线程异常：
  捕获异常，关闭 ImageProxy，记录错误并继续处理后续帧。
```

## 14. 生命周期管理

相机生命周期绑定到页面生命周期：

```text
进入测光页面：
  如果权限已授权，启动 CameraX。

页面进入后台：
  解绑 Preview 和 ImageAnalysis。

页面回到前台：
  重新绑定 UseCase。

页面销毁：
  关闭分析 executor。
```

建议 `CameraController` 提供：

```kotlin
interface CameraController {
    fun start()
    fun stop()
    fun release()
}
```

`release()` 必须关闭 executor，避免线程泄漏。

## 15. V1 实现顺序

推荐实现顺序：

```text
1. Camera 权限处理。
2. CameraX Preview 跑通。
3. ImageAnalysis 读取 Y 平面。
4. Camera2 Interop 读取曝光时间、ISO、光圈。
5. 平均测光 EV 计算。
6. ISO + EC + 快门/光圈组合生成。
7. 点击区域测光。
8. 取景框 Overlay。
9. 校准偏移设置。
```

这个顺序可以先把端到端链路跑通，再补交互和精度能力。

## 16. 测试建议

优先测试纯逻辑模块：

```text
EvCalculator
ExposurePairGenerator
PrimaryRecommendationSelector
PreviewCoordinateMapper
```

建议覆盖：

```text
1. 给定曝光时间、ISO、光圈和亮度，EV100 计算正确。
2. 平均测光 ROI 覆盖完整帧。
3. 点击测光 ROI 半径为短边 10% 的一半。
4. 坐标归一化点能正确映射到 ImageProxy 坐标。
5. EV_target 能正确应用 ISO 和曝光补偿。
6. 主推荐满足安全快门规则。
```

CameraX 相关代码以集成测试和真机验证为主。V1 至少需要在一台真实 Android 设备上验证：

```text
1. 权限流程。
2. 预览启动与停止。
3. ImageAnalysis 不阻塞预览。
4. 元数据可读取。
5. 点击测光区域与视觉 Overlay 位置一致。
```
