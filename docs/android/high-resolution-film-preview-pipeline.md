# Android 高分辨率胶片预览与风险分析技术方案

## 1. 文档状态

- 版本：V1.0
- 平台：Android
- 适用范围：专业模式冻结风险预览、胶片预览模式风险预警与曝光模拟
- 关联文档：
  - [冻结画面曝光风险预览设计](exposure-risk-preview-design.md)
  - [胶片预览模式跨平台技术方案](../shared/film-preview-technical-design.md)

## 2. 背景与问题

当前 Android 实现使用 CameraX `ImageAnalysis` 的同一帧同时生成冻结 Bitmap 和
`ExposureSnapshot`，保证了图像、曝光元数据和风险计算的时间一致性。但现有
管线存在三个分辨率瓶颈：

1. `ImageAnalysis` 没有声明目标分辨率，CameraX 可能协商为较低尺寸。
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

- 冻结 Bitmap、基础 ExposureMap 和曝光元数据必须来自同一个 `ImageProxy`。
- 探测帧只能在时间戳、revision、尺寸和曝光方向满足要求时参与风险细化。
- Preview、ImageAnalysis、风险图和冻结图继续共享同一 CameraX `ViewPort`。
- 分辨率或 GPU 能力不足时允许质量降级，但不能改变曝光公式、胶片宽容度和
  风险阈值。

## 4. 总体架构

```text
CameraX ImageAnalysis: YUV_420_888
             |
             +-- Camera2 metadata by SENSOR_TIMESTAMP
             |
             v
      Immutable frame bundle
      ├── cropped/rotated source Bitmap
      ├── camera setting EV100
      ├── calibration offset
      ├── timestamp/revision
      └── statistical ExposureMap
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
| ImageAnalysis 输入 | 优先 1920x1440 | 同帧颜色图、Y 平面和元数据 |
| 冻结 Bitmap | ViewPort 裁剪后的输入尺寸 | UI 原图和模拟输入 |
| ExposureMap | 长边 480 px | EV、裁切和局部细节统计 |
| 模拟输出 | 与冻结 Bitmap 完全相同 | 曝光模拟显示 |
| 风险显示纹理 | ExposureMap 尺寸，由 GPU/Compose 放大 | 半透明风险蒙层 |

## 5. 相机输入与同帧模型

### 5.1 分辨率选择

`ImageAnalysis` 使用 `ResolutionSelector`，首选 `1920x1440`，并允许 CameraX
按设备能力选择最接近的更高或更低尺寸。选择该档位的原因：

- 4:3 传感器帧经过 2:3 竖屏 ViewPort 裁剪后通常仍可得到约 1280x1920。
- 对 1080 至 1440 宽度的手机预览不需要大倍率放大。
- 单帧 ARGB Bitmap 约 10 MB，仍处于可控范围。

运行时必须记录实际 `ImageProxy`、`cropRect`、冻结 Bitmap 和预览容器尺寸。
不能把请求尺寸当作设备最终输出尺寸。

### 5.2 原子快照

`CapturedExposureFrame` 保持以下原子关系：

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

Bitmap 和 ExposureMap 均在关闭 `ImageProxy` 前完成拷贝。任何配置 revision
变化都会废弃当前结果。

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
- `rawLuminance`：采样点 Y 值均值，用于探测帧局部细节比较。
- `clippedHighlights`：至少 25% 有效采样达到裁切阈值。

该方法比中心单点采样更不容易遗漏细小亮区，也避免直接平均大范围区域导致
边缘过度扩散。

### 6.3 探测帧

基准图和探测图必须使用相同 ExposureMap 尺寸。现有版本先保持归一化坐标逐点
比较；后续若真机验证发现手持位移造成明显误判，再增加低分辨率平移配准。

探测结果必须满足：

- 曝光方向正确，实际 setting EV 变化至少 0.8 EV。
- 时间戳晚于基准帧。
- revision、宽高一致。
- 总处理硬截止前完成。

超时或不兼容时使用基准图的保守风险，不阻塞最终结果。

## 7. 全分辨率曝光模拟

### 7.1 禁止使用风险图重建亮度

风险 ExposureMap 是统计数据，不能作为模拟图的空间亮度源。模拟器必须从冻结
Bitmap 的每个原始 RGB 像素独立计算亮度：

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

输出 framebuffer 宽高必须与输入 Bitmap 完全相同。

### 7.2 GPU 路径

Android 26 及以上统一使用 OpenGL ES 2.0 离屏渲染：

1. 创建独立 EGL pbuffer context。
2. 上传冻结 Bitmap 为 `GL_TEXTURE_2D`。
3. Fragment Shader 执行 sRGB 线性化、逐像素 EV、胶片响应和重新编码。
4. 渲染到与源图同尺寸的 FBO。
5. `glReadPixels` 回读为 ARGB_8888 Bitmap。
6. 在 `finally` 中释放 texture、FBO、program、surface 和 context。

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

内部硬截止设为 1350 ms，为 UI 调度保留 150 ms：

| 阶段 | P95 预算 |
| --- | ---: |
| 等待并复制同帧基准数据 | 120 ms |
| 生成 480 px ExposureMap | 80 ms |
| 必要的探测帧 | 700 ms |
| 风险统计 | 150 ms |
| GPU 全分辨率模拟与回读 | 150 ms |
| UI 提交 | 30 ms |
| 总计 | 1230 ms |

探测属于可细化结果。达到硬截止时必须取消后续等待并提交已有基准风险；不能让
两个独立 800 ms 超时串行叠加。

## 9. 线程与资源管理

- CameraX Analyzer 保持单线程，使用 `KEEP_ONLY_LATEST`。
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
6. 真机采集性能数据，再决定是否增加探测帧配准和 GPU 纹理直显。
