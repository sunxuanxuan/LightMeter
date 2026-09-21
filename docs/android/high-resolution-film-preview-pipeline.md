# Android 高分辨率胶片预览与风险分析技术方案

## 1. 文档状态

- 版本：V1.0
- 平台：Android
- 适用范围：专业模式冻结风险预览、胶片预览模式风险预警与曝光模拟
- 关联文档：
  - [冻结画面曝光风险预览设计](exposure-risk-preview-design.md)
  - [胶片预览模式跨平台技术方案](../shared/film-preview-technical-design.md)

## 2. 背景与问题

Android 初版使用 CameraX `ImageAnalysis` 的同一帧同时生成冻结 Bitmap 和
`ExposureSnapshot`。该路径便于保证时间一致性，但分析流协商分辨率不足以作为
最终照片源。当前实现将冻结成片改为 `ImageCapture`，分析流继续负责邻近时刻的
测光快照。

1. `ImageAnalysis` 即使声明首选分辨率，CameraX 仍可能因设备流组合协商为较低尺寸。
2. ExposureMap 长边固定为 240 px，并以单点采样生成，细小高光、阴影和边缘
   容易漏采或产生混叠。
3. 曝光模拟先把原图限制到 720 px，再使用低分辨率 ExposureMap 决定每个输出
   像素的目标亮度。放大输出尺寸不能恢复已经被低分辨率曝光场替换的亮度纹理。

因此，风险分析分辨率和模拟成像分辨率不能继续共用同一个像素场。

## 3. 目标与约束

### 3.1 功能目标

1. 风险图使用可靠的线性 Y 平面和同帧曝光元数据，能够稳定估算高光、阴影和
   ISP 裁切风险。
2. 模拟图输出宽高与冻结原图完全一致，不通过低分辨率风险图重建原图亮度。
3. 从冻结请求到风险图和模拟图可用的 P95 时间不超过 1.5 s。

### 3.2 硬约束

- Preview、ImageAnalysis 和 ImageCapture 共享同一 CameraX `ViewPort`。
- 高分辨率 Bitmap 来自 ImageCapture；曝光建议使用冻结请求附近的分析帧快照。
- 分辨率或 GPU 能力不足时允许质量降级，但不能改变曝光公式、胶片宽容度和
  风险阈值。

## 4. 总体架构

```text
CameraX
├── ImageAnalysis: 实时测光与邻近曝光快照
└── ImageCapture: 高分辨率冻结 Bitmap
             |
             v
      Freeze result
      ├── cropped/rotated high-resolution Bitmap
      ├── nearby camera setting EV100
      ├── calibration offset
      └── analysis ExposureMap
             |
             +--> ExposureRiskCalculator --> medium-resolution risk mask
             |
             +--> FilmExposureSimulator
                    ├── OpenGL ES full-resolution renderer
                    └── full-resolution CPU fallback
```

风险路径和模拟路径消费同一个不可变帧，但使用不同空间分辨率：

| 数据 | 默认尺寸 | 用途 |
| --- | --- | --- |
| ImageAnalysis 输入 | 优先 1920x1440 | 实时测光 |
| ImageCapture 输入 | 设备可用最高分辨率 | 下载成片的原始输入 |
| PreviewView 截图 | 屏幕取景尺寸 | 点击后的静态加载占位，不参与模拟 |
| 冻结预览 Bitmap | 最长边 1920 px，不执行放大 | UI 原图和即时模拟输入 |
| 下载模拟输出 | 与 ImageCapture 裁剪结果相同 | 保存到系统相册 |
| ExposureMap | 长边 480 px | EV、裁切和局部细节统计 |
| 模拟输出 | 与冻结 Bitmap 完全相同 | 曝光模拟显示 |
| 风险显示纹理 | ExposureMap 尺寸，由 GPU/Compose 放大 | 半透明风险蒙层 |

## 5. 相机输入与同帧模型

### 5.1 分辨率选择

`ImageAnalysis` 使用 `ResolutionSelector`，首选 `1920x1440`。`ImageCapture`
使用 `CAPTURE_MODE_MINIMIZE_LATENCY` 和设备可用最高分辨率。点击冻结时先用
PreviewView 当前画面作为静态加载占位，但所有正式处理只使用随后返回的单张
ImageCapture 图像。捕获完成后保留原始 Bitmap，同时生成最长边 1920 px 的预览
副本；小于该尺寸的输入不放大。

- 预览副本在 2:3 竖屏下通常为 `1280x1920`，高于 1080p。
- 下载输出保持设备 ImageCapture 经 2:3 ViewPort 裁剪后的原始像素尺寸。
- PreviewView 截图不得参与胶片模拟、下载或曝光计算。

运行时必须记录实际 `ImageProxy`、`cropRect`、冻结 Bitmap 和预览容器尺寸。
不能把请求尺寸当作设备最终输出尺寸。

### 5.2 原子快照

`CapturedExposureFrame` 表示唯一的正式高分辨率样本：

```text
requestId
timestampNs
source Bitmap
ExposureSnapshot
    ├── ExposureMap
    ├── meteredEV100
    ├── timestampNs
    └── revision
```

高分辨率捕获失败时结束冻结并提示重试，不使用加载占位图生成结果。任何配置
revision 变化都会废弃当前结果。

## 6. 风险分析图

### 6.1 尺寸

ExposureMap 默认长边从 240 提升到 480。2:3 竖屏对应约 320x480，即 153,600
个单元。该尺寸在边缘定位和积分图内存之间较均衡：

- 单张 `FloatArray + ByteArray + BooleanArray` 约 0.9 MB。
- 三张基准/探测图和局部积分图仍可控制在几十 MB 内。
- 相对 1440 px 预览的最大显示放大倍率约为 3 倍。

### 6.2 区域统计采样

每个 ExposureMap 单元不再只读取中心 Y 像素，而是在该单元覆盖的源图区域内做
分层采样。首版采用四个四分位采样点：

```text
(0.25, 0.25) (0.75, 0.25)
(0.25, 0.75) (0.75, 0.75)
```

每个采样点经过统一的 Preview 到 Image 坐标变换。单元输出：

- `pixelEV100`：采样点线性亮度均值计算得到的 EV。
- `clippedHighlights`：至少 25% 有效采样达到裁切阈值。

该方法比中心单点采样更不容易遗漏细小亮区，也避免直接平均大范围区域导致
边缘过度扩散。

### 6.3 单帧一致性

风险蒙层只使用冻结 `ImageProxy` 生成的 ExposureMap，不采集或模拟额外曝光帧。
因此不存在帧间平移；蒙层、模拟图和冻结原图共享同一个 cropRect、旋转角度和
时间戳。高光裁切与宽容度阈值采用保守语义，不能用于区分固有纯白/纯黑物体。

超时或不兼容时使用基准图的保守风险，不阻塞最终结果。

## 7. 全分辨率曝光模拟

### 7.1 禁止使用风险图重建亮度

风险 ExposureMap 是统计数据，不能作为高分辨率模拟图的空间亮度源。
ImageCapture Bitmap 与分析 ExposureMap 尺寸不同或帧来源不同时，一次性相机
模拟器必须从冻结 Bitmap 的每个原始 RGB 像素独立计算亮度：

$$
Y_{source}=0.2126R_{linear}+0.7152G_{linear}+0.0722B_{linear}
$$

$$
EV_{pixel,100}
=
EV_{setting,100}
+
\log_2\left(\frac{Y_{source}}{0.18}\right)
+
calibrationOffset
$$

$$
\Delta EV=EV_{pixel,100}-EV_{preset,100}
$$

胶片响应曲线得到目标亮度 $Y_{target}$ 后，以线性 RGB 等比例缩放保留色相：

$$
RGB_{output}
=
RGB_{linear}
\cdot
\frac{Y_{target}}{\max(Y_{source},\epsilon)}
$$

分析 ExposureMap 只用于近似颗粒密度包络和曝光建议。冻结页先模拟 1920 px
工作副本；用户点击下载后，再对原始 ImageCapture Bitmap 执行全分辨率模拟。
每次模拟的输出 framebuffer 宽高必须与该次输入 Bitmap 完全相同。

### 7.2 专业模式曝光补偿

专业模式不套用一次性相机的胶片响应曲线，只在线性 RGB 中应用曝光补偿：

$$
RGB_{output}=clamp(RGB_{source}\times 2^{EC},0,1)
$$

`EC = 0` 时直接复制冻结 Bitmap，输出像素必须与原图完全相同。宽容度参数只
参与风险蒙层，不改变专业模式模拟图。

### 7.3 GPU 路径

Android 26 及以上统一使用 OpenGL ES 2.0 离屏渲染：

1. 创建独立 EGL pbuffer context。
2. 上传冻结 Bitmap 为 `GL_TEXTURE_2D`。
3. Fragment Shader 执行 sRGB 线性化、逐像素 EV、胶片响应和重新编码。
4. 渲染到与源图同尺寸的 FBO。
5. `glReadPixels` 回读为 ARGB_8888 Bitmap。
6. H&D、横向低通、纵向低通三个 pass 复用两张全分辨率纹理，并使用 1x1
   EGL pbuffer，控制全分辨率下载渲染的峰值内存。
7. 在 `finally` 中释放 texture、FBO、program、surface 和 context。

EGL 初始化、shader 编译、FBO 不完整或 GL 错误时进入 CPU 回退，不允许导致
冻结流程失败。

### 7.3 CPU 回退

CPU 回退使用与 shader 相同的逐像素公式，并保持输入尺寸。为控制时间：

- sRGB 到线性 RGB 使用 256 项 LUT。
- 不再执行 ExposureMap 双线性采样。
- 不再创建 720 px 中间 Bitmap。

CPU 回退优先保证尺寸和正确性；如果低端设备仍超过预算，需要在后续版本把 GPU
结果直接显示为纹理，避免 FBO 回读，而不是重新降低输出尺寸。

## 8. 时间预算

点击后应立即显示 PreviewView 静态占位；高分辨率捕获允许独立等待：

| 阶段 | P95 预算 |
| --- | ---: |
| 静态占位提交 | 1 帧内 |
| ImageCapture 高分辨率捕获 | 最长 5 s |
| 1920 px 预览模拟 | 捕获完成后异步执行 |
| 全分辨率模拟 | 仅在用户点击下载后执行 |

UI 不等待 ImageCapture 即停止显示动态取景；高分辨率结果完成后一次性替换占位，
不得先后展示分析帧结果和 ImageCapture 结果。

## 9. 线程与资源管理

- CameraX Analyzer 保持单线程，使用 `KEEP_ONLY_LATEST`。
- 实时取景只执行直方图测光并缓存最近相机曝光参数；胶片预览冻结不再额外抓取
  ImageAnalysis Bitmap 或完整 ExposureMap。
- 风险计算和 GPU 离屏渲染在 `Dispatchers.Default` 执行。
- UI 线程只接收不可变 Bitmap 和风险结果。
- 替换冻结图、模拟图或捕获请求时及时 recycle 不再使用的 Bitmap。
- GPU renderer 每次调用独占 EGL context，避免跨协程线程复用上下文。

## 10. 可观测性

Debug 构建记录以下指标：

```text
analysis image size / cropRect / rotation
frozen bitmap size
exposure map size
simulation output size
exposure map duration
GPU or CPU renderer
simulation duration
freeze-to-result duration
```

不得在 release 高频输出逐帧日志。

## 11. 测试与验收

### 11.1 单元测试

- 胶片响应曲线锚点、单调性和边界。
- CPU 逐像素模拟保持输入宽高。
- 均匀灰图输出均匀且符合响应曲线。
- 相邻不同亮度像素在输出中仍保持可区分，防止低分辨率曝光场抹平纹理。
- ExposureMap 区域采样正确处理旋转、cropRect 和行跨度。
- 单帧高低光阈值、裁切兜底和渐进 Alpha 正确。

### 11.2 真机验收

- 三档设备记录实际分析分辨率和 P50/P95 时间。
- 模拟输出宽高与冻结 Bitmap 完全相同。
- 风险图长边为 480，设备协商分辨率不足时仍不超过源图有效分辨率。
- 文字、斜线、树叶和织物纹理在模拟前后没有分辨率级别的块化。
- 灰阶卡和高反差场景中，风险区域与包围曝光结果方向一致。
- 从冻结请求到最终结果 P95 小于 1.5 s。

## 12. 实施顺序

1. 配置 CameraX 分辨率并增加尺寸/耗时观测。
2. ExposureMap 提升到 480 px并改为区域统计采样。
3. ExposureMap 保存 `calibrationOffset`，供同帧模拟使用。
4. 实现 OpenGL ES 全分辨率曝光模拟。
5. 实现全分辨率 CPU 回退与单元测试。
6. 真机采集性能数据，再决定是否增加 GPU 纹理直显。
