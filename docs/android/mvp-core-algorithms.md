# LightMeter MVP 核心算法技术文档

## 1. 文档目标

本文档描述 LightMeter MVP 当前已经实现的三项核心算法：

1. 从 Android 相机预览帧估算场景 `EV100`。
2. 根据 `EV100`、胶片 ISO 和曝光补偿生成光圈/快门组合。
3. 根据手机镜头参数、目标胶片画幅和焦段计算等效取景范围。

本文档以当前代码实际行为为准，不代表后续专业标定版本的最终实现。

核心实现位置：

```text
android/app/src/main/java/com/lightmeter/app/metering/MeteringAnalyzer.kt
android/app/src/main/java/com/lightmeter/app/ui/MeteringViewModel.kt
android/app/src/main/java/com/lightmeter/app/ui/MeteringScreen.kt
android/app/src/main/java/com/lightmeter/app/camera/CameraController.kt
android/app/src/main/java/com/lightmeter/app/exposure/ExposureModels.kt
```

***

## 2. EV 测算算法

### 2.1 目标

从 CameraX `ImageAnalysis` 返回的 YUV 预览帧和 Camera2 曝光元数据中估算当前场景的 `EV100`。

支持：

- 全局平均测光。
- 点击区域测光。
- 实时 EV 平滑。
- 画面定格后锁定 EV。

### 2.2 输入

每次计算需要以下数据：

```text
ImageProxy
  - YUV Y 平面
  - 图像宽高
  - rowStride
  - pixelStride
  - rotationDegrees
  - image timestamp

Camera2 CaptureResult
  - SENSOR_TIMESTAMP
  - SENSOR_EXPOSURE_TIME
  - SENSOR_SENSITIVITY
  - LENS_APERTURE

MeteringConfig
  - 测光模式
  - 点击位置
  - 点测光面积比例
  - 中央区域面积比例
  - 中央测光权重
  - calibrationOffset
```

如果单帧 `CaptureResult` 没有返回光圈，则使用：

```text
CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
```

中的首个光圈值作为固定光圈兜底。

### 2.3 图像帧与曝光元数据匹配

CameraX 图像帧和 Camera2 `CaptureResult` 分别通过时间戳缓存和匹配。

优先级：

```text
1. 使用完全相同 SENSOR_TIMESTAMP 的元数据。
2. 如果没有精确匹配，使用与图像帧相差不超过 50ms 的最近元数据。
3. 如果仍然没有有效元数据，跳过当前帧。
```

元数据缓存最多保留 24 项，避免持续增长。

### 2.4 分析频率

EV 不需要跟随相机帧率逐帧更新。

当前节流配置：

```text
ANALYSIS_INTERVAL_NS = 200ms
```

因此目标更新频率约为：

```text
10Hz
```

### 2.5 测光区域

#### 点测光

点测光使用圆形 ROI。没有点击位置时，圆心位于画面中心；用户点击预览后，UI 坐标先归一化为：

```text
x = 0.0 ~ 1.0
y = 0.0 ~ 1.0
```

再根据相机帧旋转角度映射到 ImageProxy 坐标。

圆形 ROI 的面积等于整帧面积的 `n%`：

```text
targetArea = imageWidth × imageHeight × n / 100
radius = sqrt(targetArea / PI)
```

默认 `n = 5`，设置范围为 `1%–10%`，调整步长为 `1%`。

#### 中央区域平均测光

中央区域平均测光复用圆形 ROI，但圆心固定在取景框中心，不计入区域外像素。
该模式主要用于还原具有明确测光接受角的机型。Olympus 35 SP 的 42mm 镜头在
135 画幅下，原厂 `20°` 平均测光接受角对应约 `19.9%` 画面面积，因此预设取
整为 `20%`。

#### 中央重点测光

中央重点测光将画面分为中央矩形区域和外围区域。中央矩形保持与整帧相同的宽高比，宽高缩放系数为：

```text
scale = sqrt(x / 100)
centerWidth = imageWidth × scale
centerHeight = imageHeight × scale
```

因此中央区域可以在 `5%–80%` 范围内始终保持准确的实际面积：

```text
中央区域面积 = 整帧面积 × x%
外围区域面积 = 整帧面积 × (1-x%)
```

分别计算两个区域的裁剪线性平均亮度，再按权重合成：

```text
Y_measured =
    Y_center × y%
    + Y_outer × (1-y%)
```

默认：

```text
x = 25
y = 70
```

设置范围：

```text
中央区域面积：5%–80%，步长 5%
中央测光权重：50%–95%
```

设置中还提供经典机型测光预设：

```text
Canon New F-1：
    中央区域面积 = 60%
    中央测光权重 = 70%

Canon AL-1：
    中央区域面积 = 40%
    中央测光权重 = 65%

Olympus 35 SP 平均（20°）：
    中央圆形区域面积 = 20%

Olympus 35 SP 点测（6°）：
    中央圆形区域面积 = 2%
```

选择机型预设会自动切换到对应测光模式，但不修改用户设置的焦段。Olympus
35 SP 的面积值由原机 42mm 镜头和 24×36mm 画幅换算，分别约为 `19.9%`
与 `1.8%`，取整为 `20%` 与 `2%`。用户手动修改区域面积或权重后，机型预设
状态恢复为自定义。

#### 全局平均测光

平均测光对模拟取景框内的全部像素采样，不区分区域权重：

```text
ROI = simulated viewfinder frame
```

三种测光模式都会先将 ImageProxy 采样点映射到 `PreviewView.FILL_CENTER` 坐标，并过滤掉模拟取景框外的像素。点测光面积和中央重点面积也都以模拟取景框面积为基准计算。

#### 预设和点击行为

默认预设为中央重点测光。用户可以在设置中选择点测光、中央重点测光或平均测光。

无论当前预设为何种模式，点击取景画面都会临时进入以点击位置为圆心的点测光。“恢复测光预设”操作会清除点击位置并恢复用户选择的预设；如果预设本身是点测光，则恢复到画面中心点测光。

### 2.6 Y 平面采样

为降低实时分析的 CPU 消耗，横向和纵向均每隔 4 个像素采样一次：

```text
SAMPLE_STEP = 4
```

Y 平面读取位置：

```text
index = y × rowStride + x × pixelStride
```

所有采样值写入一个 256 桶直方图：

```text
histogram[0..255]
```

### 2.7 裁剪平均亮度

为了降低纯黑阴影、灯光、高光反射等极端区域的影响，使用 trimmed mean。

步骤：

```text
1. 从直方图低端去掉最暗 5% 样本。
2. 从直方图高端去掉最亮 5% 样本。
3. 对剩余样本执行 gamma 反变换。
4. 计算线性亮度均值。
```

gamma 反变换：

```text
Y_normalized = Y / 255
Y_linear = Y_normalized^2.2
```

最终：

```text
Y_measured =
    sum(Y_linear × pixelCount) / retainedPixelCount
```

为避免 `log2(0)`：

```text
Y_measured >= 1e-6
```

### 2.8 EV100 计算

首先根据手机当前曝光参数计算曝光设置对应的 `EV100`：

```text
t = exposureTimeNs / 1,000,000,000

EV_setting =
    log2(N² / t)
    - log2(ISO_camera / 100)
```

其中：

- `N`：手机镜头光圈。
- `t`：手机曝光时间，单位秒。
- `ISO_camera`：手机传感器 ISO。

然后根据测光区域亮度相对 18% 中灰进行修正：

```text
EV100 =
    EV_setting
    + log2(Y_measured / 0.18)
    + calibrationOffset
```

其中：

```text
TARGET_LUMINANCE = 0.18
```

### 2.9 EV 时间平滑

普通实时测光使用指数平滑：

```text
EV_filtered =
    EV_previous × 0.75
    + EV_current × 0.25
```

切换测光模式或点击位置后提高响应速度：

```text
EV_filtered =
    EV_previous × 0.40
    + EV_current × 0.60
```

### 2.10 定格逻辑

点击定格按钮后：

```text
1. 从 PreviewView 获取当前 Bitmap。
2. 从 ImageAnalysis 保留最近一帧低分辨率逐像素 EV100 图。
3. ViewModel 进入 isFrozen 状态。
4. 保留最后一次 EV100。
5. 丢弃后续 MeteringResult。
6. 根据当前 ISO、光圈、快门和宽容度生成曝光风险蒙层。
7. 用户仍然可以调整 ISO、曝光补偿、曝光组合和宽容度。
```

风险预览只统计模拟取景框内部。高光超过默认 `+4 EV` 时显示红色，
暗部低于默认 `-3 EV` 时显示绿色；两个阈值都可按 `1/3 EV` 调整，实际
越界判断统一量化到 `0.1 EV`。

返回实时模式后重新接收 EV 更新。

***

## 3. EV 匹配光圈与快门算法

### 3.1 输入

```text
EV100_metered
ISO_film
exposureCompensation
frameFormat
focalLengthMm
1/3 档光圈表
1/3 档快门表
```

### 3.2 目标曝光 EV

测得的 `EV100` 需要结合胶片 ISO 和曝光补偿：

```text
EV_target =
    EV100_metered
    + log2(ISO_film / 100)
    - exposureCompensation
```

曝光补偿使用减号：

```text
+1EV：希望成片更亮，目标 EV 降低 1
-1EV：希望成片更暗，目标 EV 提高 1
```

示例：

```text
EV100_metered = 12
ISO_film = 400
exposureCompensation = +1

EV_target = 12 + log2(4) - 1
EV_target = 13
```

### 3.3 曝光档位表

当前光圈表覆盖：

```text
f/1 ~ f/22
```

当前快门表覆盖 `1/2000s` 至 `8s`，相邻档位为 `1/3 EV`。显示采用相机常见
标称值，例如：

```text
1/2000s、1/1600s、1/1250s、1/1000s
1/125s、1/100s、1/80s、1/60s
1/4s、1/3s、0.4s、1/2s、0.6s、0.8s
1s、1.3s、1.6s、2s、2.5s、3.2s、4s、5s、6s、8s
```

光圈使用摄影常见的 1/3 档显示值；快门内部秒数按精确 `2^(1/3)` 比例生成，
避免标称分母取整造成联动误差累积。

### 3.4 单组曝光 EV

任意光圈/快门组合对应：

```text
EV_pair = log2(N² / t)
```

其中：

- `N`：胶片机光圈。
- `t`：胶片机快门时间，单位秒。

与目标 EV 的误差：

```text
error = abs(EV_pair - EV_target)
```

### 3.5 等效曝光组合生成

当前算法以光圈为主索引：

```text
for each aperture:
    遍历所有 shutter
    选择 error 最小的 shutter
```

得到每个光圈对应的最佳快门后，保留：

```text
error <= 1/6EV
```

的组合。

由于标准档位的显示值是近似小数，相邻光圈有可能匹配到同一个快门标签。候选结果按 EV 误差从小到大执行唯一性约束：

```text
1. 光圈标签不能重复。
2. 快门标签不能重复。
3. 发生冲突时保留 EV 误差更小的组合。
```

`1/6EV` 是 1/3 档步进的一半，可视为最近档位的合理容差。

```text
返回误差最小的 8 组组合
```

### 3.6 默认主推荐

首先根据焦距筛选满足手持安全快门的组合：

```text
20mm ~ 35mm：快门不慢于 1/30s
36mm ~ 50mm：快门不慢于 1/60s
51mm ~ 90mm：快门不慢于 1/125s
91mm ~ 150mm：快门不慢于 1/250s
```

然后按常用光圈顺序选择：

```text
f/5.6
f/8
f/4
f/11
f/2.8
f/16
```

如果没有满足安全快门的组合，则从全部组合中选择。

同优先级下依次比较：

```text
1. EV 误差更小。
2. 快门时间更短。
```

### 3.7 用户手动选择

用户可以滑动光圈或快门滚轮。

两侧滚轮共享同一个 `ExposurePair` 索引，因此：

```text
滑动光圈 -> 快门同步切换
滑动快门 -> 光圈同步切换
```

用户选择后保存：

```text
selectedAperture
```

实时 EV 发生变化时：

```text
1. 根据新 EV_target 重新生成组合。
2. 查找与 selectedAperture 最接近的光圈。
3. 自动匹配该光圈在新 EV 下的快门。
```

因此用户的景深意图可以保留，快门会随测光结果变化。

***

## 4. 镜头等效视野算法

### 4.1 目标

根据手机主摄真实光学参数、胶片门尺寸和胶片镜头焦距，在手机预览上计算目标相机的等效取景范围。

当前实现通过中心取景框和框外遮罩模拟目标视野，不改变手机镜头物理焦距。

### 4.2 手机光学参数

从 Camera2 `CameraCharacteristics` 读取：

```text
SENSOR_INFO_PHYSICAL_SIZE
LENS_INFO_AVAILABLE_FOCAL_LENGTHS
```

得到：

```text
phoneSensorWidth
phoneSensorHeight
phoneFocalLength
```

当前使用可用焦距列表中的首个焦距。

如果设备没有返回有效参数，使用降级基准：

```text
sensor = 36mm × 24mm
focalLength = 24mm
```

即按全画幅 24mm 广角视野近似。

### 4.3 目标画幅参数

当前画幅：

```text
135：
    frame = 36mm × 24mm

APS-C：
    frame = 23.6mm × 15.7mm

6×4.5：
    frame = 56mm × 41.5mm

6×6：
    frame = 56mm × 56mm
```

目标焦距候选范围为 `20mm ~ 150mm`，步长为 `1mm`。实际滑块范围由当前
画幅和设备硬件倍率上下限反算后与候选范围求交得到。

App 当前固定为竖屏，因此计算时使用：

```text
portraitWidth = min(frameWidth, frameHeight)
portraitHeight = max(frameWidth, frameHeight)
```

### 4.4 PreviewView 可见传感器范围

`PreviewView` 使用：

```text
ScaleType.FILL_CENTER
```

因此手机相机原始图像可能为了填满预览区域而发生中心裁剪。

先计算：

```text
viewAspect = viewWidth / viewHeight
sensorAspect = sensorPortraitWidth / sensorPortraitHeight
```

如果预览比传感器画面更宽：

```text
displayedSensorWidth = sensorPortraitWidth
displayedSensorHeight = sensorPortraitWidth / viewAspect
```

否则：

```text
displayedSensorHeight = sensorPortraitHeight
displayedSensorWidth = sensorPortraitHeight × viewAspect
```

这一步得到手机预览当前真正可见的传感器投影范围。

### 4.5 目标画幅投影

根据针孔模型和相似三角形，目标胶片视野投影到手机镜头平面上的尺寸为：

```text
targetProjectionWidth =
    phoneFocalLength
    × targetFramePortraitWidth
    / targetFilmFocalLength

targetProjectionHeight =
    phoneFocalLength
    × targetFramePortraitHeight
    / targetFilmFocalLength
```

换算为当前手机预览的比例：

```text
widthFraction =
    targetProjectionWidth / displayedSensorWidth

heightFraction =
    targetProjectionHeight / displayedSensorHeight
```

基础比例保留原始值，用于计算自动变焦倍率：

```text
fitZoom = 1 / max(widthFraction, heightFraction)
```

目标倍率限制在设备的 `minZoomRatio ~ maxZoomRatio` 范围。

### 4.6 取景框生成

CameraX 回传实际倍率后，最终取景框尺寸：

```text
frameWidth =
    previewWidth × clamp(widthFraction × actualZoom, 0, 1)

frameHeight =
    previewHeight × clamp(heightFraction × actualZoom, 0, 1)
```

取景框居中：

```text
left = (previewWidth - frameWidth) / 2
top = (previewHeight - frameHeight) / 2
```

UI 在框外绘制半透明黑色遮罩，在框边缘绘制白色边框。

焦段越长时系统请求更高 CameraX 倍率。设备能够达到目标倍率时，取景框
长边填满预览；达到倍率上限后，画面保留剩余边框并显示上限提示。

```text
targetProjection 越小
fitZoom 越大
```

### 4.7 与点击测光的关系

UI 只允许用户在当前模拟取景框内部点击进入区域测光。

框外点击不会改变测光区域。

平均测光、点测光和中央重点测光均只统计模拟取景框内部。框外遮罩区域不会影响 EV。

***

## 5. 当前精度边界

### 5.1 EV 估算

绝对 EV 仍可能受到以下因素影响：

- 手机 ISP tone mapping。
- HDR 和局部亮度增强。
- YUV 输出并非严格 RAW 线性数据。
- 手机标称 F-stop 与实际 T-stop 的差异。
- ImageProxy 与 CaptureResult 最多允许 50ms 的降级错位。
- 不同设备的自动曝光目标亮度不同。

后续建议使用 18% 灰卡和专业测光表标定 `calibrationOffset`。

### 5.2 点击坐标

`Preview` 和 `ImageAnalysis` 通过同一个 CameraX `ViewPort` 和
`UseCaseGroup` 绑定。分析器只读取 `ImageProxy.cropRect`，再根据
`rotationDegrees` 映射到 PreviewView 归一化坐标，因此点击位置、模拟
取景框和曝光风险蒙层共享同一裁剪范围。

### 5.3 等效取景

当前实现是基于光学参数的中心取景框模拟：

- 不改变手机物理镜头。
- 不切换多摄像头。
- 不调用 CameraX 数字变焦。
- 使用可用焦距列表中的首个焦距作为当前主摄焦距。

对于多摄逻辑相机，后续可以读取每帧实际 `LENS_FOCAL_LENGTH` 或 active physical camera ID，提高准确度。

***

## 6. 核心公式汇总

```text
# 手机曝光参数对应的 EV100
EV_setting =
    log2(phoneAperture² / phoneExposureSeconds)
    - log2(phoneISO / 100)

# 亮度修正后的场景 EV100
EV100 =
    EV_setting
    + log2(measuredLinearLuminance / 0.18)
    + calibrationOffset

# 胶片 ISO 与曝光补偿后的目标 EV
EV_target =
    EV100
    + log2(filmISO / 100)
    - exposureCompensation

# 一组光圈和快门对应的 EV
EV_pair =
    log2(filmAperture² / shutterSeconds)

# 曝光组合误差
error =
    abs(EV_pair - EV_target)

# 目标画幅投影到手机镜头平面的尺寸
targetProjection =
    phoneFocalLength
    × targetFrameDimension
    / targetFilmFocalLength

# 目标取景范围在手机预览中的占比
viewfinderFraction =
    targetProjection / displayedPhoneSensorDimension
```
