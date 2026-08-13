# 胶片预览模式跨平台技术方案

## 1. 文档状态

- 版本：V2.0 设计基线
- 适用平台：Android、iOS
- 产品定义：[双模式产品方案](dual-mode-product-design.md)
- 现有算法参考：[胶片曝光宽容度](film-exposure-latitude-reference.md)

本文定义胶片预览模式的领域模型、计算公式、渲染管线、平台边界、测试策略和
实施顺序。专业模式继续使用现有推荐曝光算法；本文只描述新增和共享部分。

## 2. 核心原则

1. 手机相机负责采集场景和估算场景 EV，不直接冒充一次性胶片相机。
2. 一次性相机的 ISO、光圈和快门是固定预设，不写入手机相机曝光控制。
3. 模拟画面、风险图和评级必须来自同一个不可变帧快照。
4. 曝光判断与色彩、颗粒、暗角等观感效果解耦。
5. 参数或元数据不可靠时降级为“不可判断”，不输出伪精确结果。
6. 公共数学规范和测试向量共享，Kotlin 与 Swift 分别实现运行时代码。

## 3. 与现有系统的关系

现有两端已经具备以下可复用能力：

- CameraX / AVFoundation 相机采集。
- 手机曝光元数据读取。
- Y 平面线性化和逐像素 `EV100`。
- 有效取景框、revision 和稳定变焦控制。
- 原子 `ExposureSnapshot`。
- 胶片宽容度与基础风险图。
- 冻结后的 $+2 EV/-2 EV$ 细节探测帧。

新增部分：

- 顶层 `AppMode` 和模式状态隔离。
- 一次性相机预设仓库。
- 固定参数曝光参考计算。
- 胶片响应和观感渲染器。
- 预览模式评级与建议引擎。
- 预设跨平台测试数据。

不应复制第二套相机会话或测光引擎。两个模式共享 Camera 和 Metering 层，在
Domain 层分流：

```text
Camera Frame + Metadata
        |
        v
Scene Metering / Exposure Map
        |
        +--> Professional Exposure Recommendation
        |
        +--> Fixed-Camera Film Preview
```

## 4. 总体架构

```text
UI
├── ModeSelection
├── ProfessionalMetering
└── FilmPreview
        |
Presentation
├── ProfessionalMeteringViewModel
└── FilmPreviewViewModel
        |
Domain
├── MeteringEngine                  复用
├── ExposureRecommendationEngine   复用
├── ExposureRiskEngine             复用并扩展参考 EV 输入
├── DisposableCameraRepository     新增
├── FilmPreviewEngine              新增
├── SceneRatingEngine              新增
└── PreviewAdviceEngine            新增
        |
Platform
├── CameraService / CameraController
├── PreviewRenderer
└── SettingsStore
```

两个 ViewModel 不共享可变 UI State。共享服务只能通过不可变值传递结果，避免
切换模式后旧回调覆盖当前页面。

## 5. 公共领域模型

以下是平台无关的逻辑结构，不要求 Kotlin 与 Swift 使用完全相同的类型名：

```text
enum AppMode {
    PROFESSIONAL
    FILM_PREVIEW
}

enum EvidenceLevel {
    OFFICIAL
    MEASURED
    ESTIMATED
}

data class DisposableCameraPreset(
    id: String,
    schemaVersion: Int,
    presetVersion: Int,
    brand: String,
    model: String,
    regionOrBatch: String?,
    film: FilmProfile,
    optics: FixedOptics,
    shutterSeconds: Double,
    flash: FlashProfile?,
    look: FilmLookProfile?,
    sources: List<PresetSource>
)

data class FilmProfile(
    name: String,
    iso: Int,
    process: String?,
    highlightLatitudeStops: Double,
    shadowLatitudeStops: Double,
    latitudeEvidence: EvidenceLevel,
    responseCurve: FilmResponseCurve
)

data class FixedOptics(
    aperture: Double,
    focalLengthMm: Double,
    frameWidthMm: Double = 36,
    frameHeightMm: Double = 24,
    minimumFocusMeters: Double?
)

data class FlashProfile(
    effectiveDistanceMinMeters: Double,
    effectiveDistanceMaxMeters: Double,
    guideNumberAtRatedIso: Double?
)
```

约束：

- `id` 一经发布不可复用。
- 修改曝光参数必须增加 `presetVersion`。
- `ISO > 0`、`aperture > 0`、`shutterSeconds > 0`。
- 宽容度量化到 $1/3 EV$，范围为 $1/3$ 至 $8 EV$。
- 来源必须精确到参数字段，不能只给预设整体附一个来源。
- 地区或批次参数不同必须拆分预设。

## 6. 快照模型

现有 `ExposureSnapshot` 需要扩展或包装为：

```text
FilmPreviewSnapshot
├── sourceColorFrame
├── exposureMap
├── meteredEV100
├── cameraExposureMetadata
├── timestamp
├── orientationAndCropTransform
├── revision
├── sessionID
├── presetID
└── presetVersion
```

规则：

- `sourceColorFrame` 与 `exposureMap` 必须来自同一视频帧。
- 风险图、模拟画面和评级只能消费同一个 `FilmPreviewSnapshot`。
- `presetID`、版本、revision 或 session 不匹配时直接丢弃结果。
- 跨异步边界前必须取得像素缓冲所有权，不得引用已关闭的 `ImageProxy` 或已
  解锁的 `CVPixelBuffer`。

实时路径允许丢帧，不允许积压旧帧。

## 7. 固定曝光计算

### 7.1 一次性相机参考场景 EV

对预设光圈 $N_p$、快门时间 $t_p$ 和胶片感光度 $ISO_p$：

$$
EV_{preset,100}
=
\log_2\left(\frac{N_p^2}{t_p}\right)
-
\log_2\left(\frac{ISO_p}{100}\right)
$$

`EV_preset,100` 表示该固定曝光组合会把什么场景 EV 放在 18% 中灰附近。

示例：ISO 400、f/10、1/100 s：

$$
EV_{preset,100}
=
\log_2(10^2 \times 100)-\log_2(4)
\approx 11.29
$$

### 7.2 像素曝光落点

现有测光引擎输出像素场景亮度 $EV_{pixel,100}$。像素相对预设中灰的落点为：

$$
\Delta EV_{pixel}
=
EV_{pixel,100}-EV_{preset,100}
$$

含义：

- $\Delta EV=0$：映射到 18% 中灰附近。
- $\Delta EV>0$：比中灰更亮，向胶片高光肩部移动。
- $\Delta EV<0$：比中灰更暗，向胶片趾部移动。

整体场景提示可以使用：

$$
\Delta EV_{scene}
=
EV_{metered,100}-EV_{preset,100}
$$

注意：预览模式不使用专业模式的
`referenceEV100 = meteredEV100 - EC`。专业模式的参考值取决于用户选择的曝光，
预览模式的参考值始终由固定相机参数决定。

### 7.3 风险判定

对预设高光宽容度 $H$、暗部宽容度 $S$，以及预警起点 $W=1 EV$：

```text
abs(deltaEV) <= W       -> 不预警
deltaEV > W             -> 高光渐进预警
deltaEV < -W            -> 暗部渐进预警
deltaEV >= H            -> 高光达到细节丢失边界
deltaEV <= -S           -> 暗部达到细节丢失边界
```

预警蒙层透明度按偏离程度线性增加：

$$
\alpha_{highlight}
=
\alpha_{min}
+
(\alpha_{max}-\alpha_{min})
\cdot
clamp\left(
\frac{\Delta EV_{pixel}-W}{H-W},
0,1
\right)
$$

$$
\alpha_{shadow}
=
\alpha_{min}
+
(\alpha_{max}-\alpha_{min})
\cdot
clamp\left(
\frac{-\Delta EV_{pixel}-W}{S-W},
0,1
\right)
$$

当前实现中 $\alpha_{min}=0x20$（约 12.5%），
$\alpha_{max}=0xE6$（约 90%）。达到胶片宽容度边界时蒙层达到最大强度；手机
YUV 已裁切时直接使用最大强度高光预警。

Android 风险分析图最长边为 480 px，每个单元对源图对应区域执行四分位统计
采样；iOS 在完成同等性能验证前保持现有平台参数。显示时使用 GPU 高质量纹理
插值放大到冻结预览尺寸，同时不降低底层冻结图分辨率。风险分析图只用于风险
判定和蒙层，禁止作为模拟画面的空间亮度源。

该渐进规则只用于胶片预览模式。专业模式继续采用越过宽容度阈值后才显示风险
蒙层的原有规则。

## 8. 胶片响应曲线

### 8.1 目标

响应曲线把场景曝光落点转换为显示亮度，同时满足：

- 中灰锚点稳定。
- 阴影逐渐进入趾部，而不是直接黑色截断。
- 高光逐渐进入肩部，而不是线性爆白。
- 单调递增，不能产生亮度反转。
- 不改变风险引擎输入。

### 8.2 MVP 曲线

每个 `FilmResponseCurve` 定义三个锚点：

```text
(-S, 0.02)  暗部实用边界
( 0, 0.18)  18% 中灰
( H, 0.98)  高光实用边界
```

在 `[-S, 0]` 和 `[0, H]` 分别使用单调三次 Hermite 插值。区间外使用斜率趋近
于零的软延伸，并最终限制到 `[0, 1]`。禁止直接使用普通三次样条，因为过冲会
破坏单调性。

首版公共实现可以使用固定切线规则；后续用真实灰阶包围曝光样片校准锚点和
切线。测试必须覆盖：

- `output(0) == 0.18`，允许误差 `1e-4`。
- 全域单调非递减。
- 输出有限且位于 `[0, 1]`。
- 阈值附近连续，无可见跳变。

Android 冻结预览按上述三个锚点实现分段单调 `smoothstep`。在 `[-S, 0]` 和
`[0, H]` 内分别插值；超出宽容度边界后用 2 EV 的软延伸逐渐趋近纯黑或纯白。
模拟器使用 OpenGL ES 对冻结 RGB 图逐原始像素计算响应曲线，输出宽高与冻结图
完全一致；GPU 不可用时回退到使用 LUT 的同尺寸 CPU 渲染。

### 8.3 色彩保留

将源帧转换到线性 RGB，计算源亮度 $Y_{source}$。响应曲线输出目标亮度
$Y_{target}$ 后：

$$
gain
=
\frac{Y_{target}}
{\max(Y_{source}, \epsilon)}
$$

$$
RGB_{exposure}
=
clamp(RGB_{source}\times gain,0,1)
$$

该方法保留手机帧可用色度，同时用场景 EV 控制亮度。源帧已经裁切或噪声严重
时不能恢复真实色彩，风险层必须继续提示这一限制。

冻结页提供两个互斥视角，默认显示宽容度预警：

```text
宽容度预警：同帧源图 -> 渐进风险蒙层 -> 风险图例
曝光模拟：  同帧源图 -> 曝光响应模拟图
```

风险测算和曝光模拟图均生成完成后，冻结按钮旁才显示圆形眼睛按钮。用户通过
该按钮切换两个视角。恢复实时预览时自动回到默认宽容度预警视角，避免下次冻结
时误判当前展示含义。

专业模式设置保留“仅预警宽容度外”开关。开启时沿用越过宽容度边界才报警的
专业模式原逻辑；关闭时与胶片预览模式一致，在偏离中灰 `±1 EV` 后渐进预警。

专业模式冻结页同样提供圆形眼睛按钮，并使用当前冻结测光结果、曝光补偿及胶片
宽容度计算曝光模拟。按钮仅在风险结果与模拟图均准备完成后显示；模拟视角不叠加
风险蒙层和图例。

## 9. 观感层

观感层在曝光层之后执行：

```text
Exposure-mapped linear RGB
  -> optional color matrix or calibrated 3D LUT
  -> optional vignetting
  -> optional softness
  -> optional deterministic grain
  -> display color space conversion
```

要求：

- 默认效果克制，不遮挡曝光问题。
- 每个效果可以关闭。
- 颗粒随机种子由 `timestamp + presetID` 导出，冻结后不能跳动。
- 暗角只影响显示，不计入风险统计。
- 未经色卡和真实冲扫样片标定，不得以胶片官方名称宣传 LUT 的精确性。

## 10. 场景评级

评级引擎输入：

- `deltaSceneEV`。
- 取景框内高光和暗部风险比例。
- 中央主体区域风险比例。
- 手机信号裁切比例。
- 元数据稳定性和探测帧状态。

建议首版规则：

```text
不可判断：
  元数据无效、revision 不匹配或 AE 不稳定

较差：
  中央区域风险 >= 25%
  或全画面任一风险 >= 40%
  或 abs(deltaSceneEV) 超出对应宽容度 1 EV 以上

注意：
  中央区域风险 >= 8%
  或全画面任一风险 >= 15%
  或 deltaSceneEV 距离任一宽容度边界不足 1 EV

良好：
  其他稳定情况
```

这些阈值是产品初始值，不是摄影物理常数。正式发布前使用标注场景集评估误报
和漏报，调整后作为版本化测试数据管理。

建议引擎只输出结构化原因码，UI 负责本地化：

```text
AMBIENT_TOO_DARK
AMBIENT_TOO_BRIGHT
HIGH_CONTRAST
USE_FLASH
MOVE_WITHIN_FLASH_RANGE
PHONE_SIGNAL_CLIPPED
HOLD_STEADY
SUITABLE
```

## 11. 闪光灯模型

### 11.1 MVP

MVP 不修改预览像素，只输出：

- 当前环境光背景曝光。
- 是否建议使用闪光灯。
- 预设声明的有效距离。
- 无法模拟主体闪光结果的说明。

### 11.2 后续近似模型

若用户提供主体距离 $d$，且预设有额定 ISO 下闪光指数 $GN$，闪光相对正常曝光
的偏差可近似为：

$$
\Delta EV_{flash}
=
2\log_2\left(\frac{GN}{N_p d}\right)
$$

环境光与闪光的线性曝光合成为：

$$
\Delta EV_{combined}
=
\log_2\left(
2^{\Delta EV_{ambient}}
+
2^{\Delta EV_{flash}}
\right)
$$

该式只能用于被识别为主体的区域。没有深度或主体遮罩时禁止应用到整幅画面。
反射率、闪光角度和遮挡仍会造成误差，因此 UI 必须标记为近似。

## 12. 实时处理管线

```text
1. Camera 输出彩色 YUV 帧。
2. 取得与帧时间最接近的曝光元数据。
3. 根据节流、AE 稳定性、revision 和 session 过滤。
4. MeteringEngine 生成逐像素 EV 图和场景 EV。
5. FilmPreviewEngine 计算 preset EV 与 delta EV。
6. PreviewRenderer 在 GPU 上映射曝光和观感。
7. ExposureRiskEngine 生成低分辨率风险蒙层。
8. SceneRatingEngine 输出评级和原因码。
9. ViewModel 原子提交同一 frame token 的结果。
```

推荐目标：

| 指标 | 目标 |
| --- | --- |
| 相机预览输入 | 24 fps 以上 |
| 模拟画面输出 | 中高端 15-24 fps，低端不低于 10 fps |
| EV/风险分析 | 8-12 Hz |
| Android 风险图长边 | 480 px |
| Android 模拟渲染尺寸 | 与冻结原图完全一致 |
| 端到端预览延迟 | 150 ms 以内目标 |
| 主线程单帧工作 | 8 ms 以内 |

发生过热或持续掉帧时，依次降低：

1. 颗粒和观感效果。
2. 模拟渲染分辨率。
3. 风险更新频率。

不得修改曝光公式、宽容度或评级阈值。

## 13. 冻结与探测

冻结时：

1. 锁定当前 `FilmPreviewSnapshot`。
2. 立即显示基础风险图。
3. 根据候选区域按需采集 `-2 EV` 高光探测或 `+2 EV` 暗部探测。
4. 用现有局部均值和梯度积分图算法筛除固有黑白平坦区域。
5. 恢复手机曝光偏置。
6. 原子替换为精化风险图和评级。

探测帧只用于判断“改变手机曝光后是否出现新细节”，不改变一次性相机固定
曝光参考。用户取消、切换模式、进入后台或 session 中断时必须取消任务并恢复
曝光偏置。

## 14. Android 实现

建议新增：

```text
app/
├── filmpreview/
│   ├── DisposableCameraPreset.kt
│   ├── DisposableCameraRepository.kt
│   ├── FilmPreviewEngine.kt
│   ├── SceneRatingEngine.kt
│   └── FilmPreviewViewModel.kt
└── ui/
    ├── ModeSelectionScreen.kt
    └── FilmPreviewScreen.kt
```

平台实现：

- CameraX `Preview` 和 `ImageAnalysis` 继续共享 `ViewPort`。
- 为模拟画面取得与分析帧一致的 YUV 色彩数据，不能截取 `PreviewView`。
- MVP 可使用 OpenGL ES 着色器；若项目引入 Compose 图形管线，应先验证设备
  覆盖和 YUV 到 RGB 一致性。
- `ImageProxy` 关闭前复制或上传 GPU 所需平面，后台任务不得持有已关闭内存。
- Camera2 元数据优先按传感器时间戳匹配，超出允许窗口的帧丢弃。

## 15. iOS 实现

建议新增：

```text
FilmLightMeter/
├── Features/
│   ├── ModeSelection/
│   └── FilmPreview/
├── Domain/
│   └── FilmPreview/
└── Rendering/
    └── FilmPreviewRenderer.swift
```

平台实现：

- `CameraService` 继续提供 `CVPixelBuffer`、近似曝光元数据和 session 标识。
- 使用 Core Image 自定义 kernel 完成 MVP；性能或曲线能力不足时切换 Metal。
- 模拟页不直接展示 `AVCaptureVideoPreviewLayer` 作为最终画面，因为其像素无法
  与 EV 图建立严格同帧关系。
- 使用 `CVMetalTextureCache` 或 Core Image 零拷贝路径，避免每帧创建
  `CGImage`。
- iOS 无严格逐帧公开曝光元数据时，AE 快速变化阶段保留上一稳定结果，不提交
  新评级。

## 16. 预设分发与版本

当前仓库不建立共享运行时代码。预设采用以下方式保持一致：

1. `docs/shared/` 保存规范、来源和规范化 JSON 测试数据。
2. Android 与 iOS 分别打包平台资源。
3. CI 脚本比较两端导出的规范化 JSON 与公共基线。
4. 公共基线变化必须同时更新两端测试。

首版预设随 App 发布，不从网络动态下载。这样可以避免远端参数变化导致同一
App 版本结果漂移，也不需要新增网络权限或服务端。

设置只保存 `presetID + presetVersion`。加载时：

- 完全匹配：正常恢复。
- ID 存在但版本变化：显示参数已更新提示。
- ID 不存在：进入选择页，不静默替换。

## 17. 状态机

预览模式顶层状态：

```text
selectingPreset
permissionRequired
startingCamera
stabilizing
live
freezing
frozen
recoverableError
fatalError
```

关键转移：

```text
selectingPreset -> startingCamera -> stabilizing -> live
live -> freezing -> frozen
frozen -> live
任意相机状态 -> permissionRequired / recoverableError
模式切换 -> 取消任务 -> 释放预览资源
```

只有 `live` 接收实时结果；`freezing` 和 `frozen` 锁定同一基线快照。

## 18. 设置迁移

设置载荷版本升级，推荐结构：

```text
AppSettings
├── version
├── lastMode
├── showModeSelectionOnLaunch
├── professionalSettings
└── filmPreviewSettings
    ├── presetID
    ├── presetVersion
    ├── riskOverlayEnabled
    ├── filmLookEnabled
    └── flashSelection
```

从现有版本迁移时将所有字段映射到 `professionalSettings`，新增字段使用默认值。
迁移必须幂等，并为旧载荷、损坏载荷和未知枚举编写测试。

## 19. 测试策略

### 19.1 公共数值测试

- 预设 EV 公式已知输入。
- 像素 `deltaEV` 正负方向。
- 中灰、高光、暗部边界。
- 响应曲线单调性、连续性和锚点。
- 风险 Alpha 与比例。
- 评级边界和原因码。
- 无效、NaN、Infinity 和极端参数。

Android 与 iOS 使用同一组 JSON 向量，数值差异目标不超过 `0.01 EV`。

### 19.2 快照一致性测试

- 不同 timestamp、revision、session 或 presetVersion 的输入不能合并。
- 预设切换后旧异步结果必须被丢弃。
- 冻结图、风险图和评级 frame token 一致。
- 取消冻结和切换模式会恢复曝光偏置。

### 19.3 图像金样测试

建立不含隐私内容的合成场景：

- 灰阶阶梯。
- 高反差窗景。
- 固有黑色带纹理物体。
- 固有白色平坦物体。
- 手机信号裁切区域。

金样比较应分别检查：

- 曝光映射亮度。
- 风险分类掩码。
- 观感层输出。

风险测试不能依赖带颗粒的最终图像。

### 19.4 实物校准

每个正式预设至少验证：

1. 标准灰卡在多档照度下的预演落点。
2. 灰阶卡或色卡的高光、阴影边界。
3. 同型号一次性相机的实拍和统一冲扫扫描。
4. 日光、阴天、室内、逆光四类场景。
5. 至少两台 Android 与两台 iPhone 的跨设备偏差。

真实样片用于校准，不作为“完全一致”的验收标准，因为胶片批次、镜头个体和
冲扫扫描均存在变化。

## 20. 验收标准

- 固定参数参考 EV 计算误差不超过 `0.01 EV`。
- 同一平台稳定场景重复结果标准差目标不超过 `0.2 EV`。
- 模拟画面、风险图和评级使用相同 frame token。
- 关闭观感层后曝光映射和风险结论不变。
- 两端公共测试向量风险分类一致率不低于 99.5%。
- AE、预设、revision 或 session 不稳定时不提交混合结果。
- 专业模式推荐误差继续满足不超过 $1/6 EV$。
- 性能降级不改变任何领域阈值。

## 21. 实施顺序

1. 抽取共享 `AppMode`、设置载荷和导航。
2. 定义预设模型、公共 JSON 基线和校验测试。
3. 实现纯函数 `FilmPreviewEngine`、响应曲线和评级引擎。
4. 扩展帧快照，保证彩色帧与 EV 图同源。
5. 实现 Android GPU 预览和 UI。
6. 实现 iOS GPU 预览和 UI。
7. 接入冻结探测与详细建议。
8. 建立合成金样和真机性能测试。
9. 完成首发预设实物校准后再启用正式型号标签。
