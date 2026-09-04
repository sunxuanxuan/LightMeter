# 彩色负片模拟 V1 基础方案

## 1. 文档状态

- 版本：V1.0 设计草案
- 日期：2026-09-03
- 适用平台：Android，后续由 iOS 复用同一参数规范
- 实现状态：Android 已实现逐通道色彩响应与密度相关颗粒；MTF 与 halation 待后续迭代
- 关联文档：
  - [胶片预览模式跨平台技术方案](film-preview-technical-design.md)
  - [主流胶片曝光宽容度参考表](film-exposure-latitude-reference.md)
  - [Android 高分辨率胶片预览与风险分析技术方案](../android/high-resolution-film-preview-pipeline.md)

本文定义首版彩色负片成像模拟的范围、参数模型、内置胶片 profile、算法顺序、
性能策略和验证标准。本文中的数值是实现基线，不表示对厂商乳剂配方的精确复刻。

## 2. 目标与非目标

### 2.1 目标

1. 同一场景在不同胶片 profile 下呈现可辨认、稳定且不过度夸张的差异。
2. 曝光变化必须驱动趾部、直线段、肩部、色彩偏移和颗粒变化，不能退化为固定
   滤镜叠加。
3. 实时预览与冻结成片使用同一组参数和数学定义，仅允许采样质量不同。
4. 所有效果可在 scene-linear RGB 或胶片密度域中解释。
5. 参数来源和可信度可追踪，后续可用实测数据逐项替换工程初值。

### 2.2 首版不包含

- C-41、ECN-2 的药液、温度、时间、推拉冲洗和交叉冲洗差异。
- 相纸、放大机、打印灯、扫描仪、扫描软件和实验室调色风格。
- 胶片老化、保存条件、批次漂移、漏光、划痕、灰尘和片框。
- 镜头暗角、畸变、色差、眩光和一次性相机塑料镜头的成像缺陷。
- 闪光灯对主体的空间照明模拟。
- 反转片、黑白负片和即时成像相纸。

一次性相机的固定光圈、快门和闪光建议仍由现有相机预设负责。本文只描述光线
到达胶片平面后，彩色负片本身造成的响应。

胶片必须经过显影才能形成可观察密度，因此厂商数据表规定的标准显影结果被视为
底片 profile 的基准定义；V1 不模拟显影条件变化，也不提供任何冲洗风格参数。

## 3. 输出定义

真实彩色负片形成的是带片基的反相染料密度，无法不经过观察或数字化过程直接
显示为普通正像。为隔离后期冲扫影响，V1 定义一个固定的**中性数字反相**：

1. 将模拟得到的各层染料密度减去各自最小密度。
2. 用统一白点和 18% 灰锚点归一化为正像 scene-linear RGB。
3. 只执行固定的 sRGB 显示变换，不附加任何胶片 profile 专属的扫描 LUT、
   相纸曲线、自动白平衡或局部调整。

因此输出表示“该负片经中性数字观察后的近似成像”，不是某家冲扫店交付的
JPEG，也不是暗房相纸成片。

## 4. 当前实现基线

Android 当前流程为：

```text
sRGB Bitmap
  -> sRGB 解码
  -> 计算亮度 Y
  -> 根据逐像素 Delta EV 计算单通道目标亮度
  -> RGB 等比例缩放
  -> sRGB 编码
```

现有实现已经满足：

- 在线性 RGB 中处理曝光。
- 18% 灰锚点稳定。
- 趾部和肩部连续且单调。
- GPU 渲染失败时有同尺寸 CPU 回退。

但它只有单通道亮度响应，不同胶片仅有 ISO 和宽容度差异，因此不能表达：

- 三个感色层不同的响应曲线。
- 胶片固有的颜色串扰和曝光相关色偏。
- 胶片 MTF、颗粒和乳剂内高光散射。

V1 在保留现有曝光测量与风险判断的前提下替换渲染部分。

## 5. 数据来源与使用边界

### 5.1 可采用的公开数据

厂商技术资料可提供以下约束：

| 数据 | V1 用途 | 限制 |
| --- | --- | --- |
| R/G/B 特性曲线 | 拟合每层 `log exposure -> density` | 图表需数字化；通常不是原始采样值 |
| 光谱敏感度曲线 | 约束光源变化和通道串扰 | 多为相对灵敏度，不能独立恢复完整颜色 |
| 光谱染料密度 | 约束染料吸收和橙色片基 | 常只给 D-min 与中灰，不给独立染料全量数据 |
| MTF 曲线 | 约束空间频率响应 | 包含指定曝光和处理条件 |
| RMS granularity / PGI | 标定颗粒相对强弱 | PGI 与 RMS granularity 不可直接换算 |
| ISO、互易律、曝光补偿 | 曝光响应边界 | V1 暂不实现长曝光互易律 |

主要公开参考：

- [KODAK PROFESSIONAL PORTRA 400 Film, E-4050](https://kodakprofessional.com/sites/default/files/2025-07/e4050.pdf)
- [KODAK GOLD 100 and 200 Films, E-7022](https://www.manualzz.com/doc/2642302/kodak-gold-200-135-36-datasheet)
- [FUJIFILM Film Data Sheets](https://www.fujifilm.com/uk/en/consumer/support/films/negative-and-reversal)
- [FUJICOLOR PRO 400H Data Sheet](https://asset.fujifilm.com/www/uk/files/2019-09/d68b00b13f1b6234629342317be9cf2b/films_pro-400h_datasheet_01.pdf)
- [ISO 10505:2009 RMS Granularity](https://www.iso.org/obp/ui/#iso:std:50747:en)

### 5.2 公开数据不能解决的问题

仅使用厂商曲线无法唯一还原最终颜色，原因包括：

- RGB 输入已经丢失原始场景光谱，同一 RGB 可能对应不同光谱。
- 厂商通常不公开完整的独立染料吸收、层间效应和 DIR coupler 数据。
- 手机 ISP 已经执行白平衡、降噪、锐化、色彩矩阵和 tone mapping。
- 胶片数据表的测试光源、显影条件和密度计状态不等于手机输入条件。

因此 V1 使用“公开曲线约束趋势，工程参数建立可运行基线，实拍标定修正结果”的
策略，不宣称色度学级复刻。

### 5.3 开源实现的使用原则

- [spektrafilm](https://github.com/andreavolpato/spektrafilm) 可作为负片、相纸、
  光谱重建、颗粒和 halation 的架构参考。
- [Realistic Film Grain Rendering](https://www.ipol.im/pub/art/2017/192/) 可作为
  随机几何颗粒模型参考。
- [darktable grain](https://docs.darktable.org/usermanual/4.2/en/module-reference/processing-modules/grain/)
  可作为实时程序化颗粒的性能参考。

上述 spektrafilm 和 IPOL 示例代码使用 GPLv3；spektrafilm profile 另有
CC BY-SA 或自定义许可。项目不得直接复制其代码、profile 或 LUT，除非项目许可
策略已经确认兼容。V1 算法依据公开论文和厂商资料独立实现。

## 6. 领域模型

曝光风险参数和成像参数必须分离：

```text
FilmProfile
├── identity
│   ├── id
│   ├── displayName
│   ├── nominalIso
│   └── profileVersion
├── latitude
│   ├── shadowStops
│   ├── highlightStops
│   └── evidence
└── imaging
    ├── response
    ├── color
    ├── spatial
    ├── grain
    ├── halation
    └── evidenceByField
```

建议的跨平台结构：

```text
data class NegativeFilmImagingProfile(
    id: String,
    profileVersion: Int,
    nominalIso: Int,
    response: RgbDensityResponse,
    color: NegativeColorModel,
    spatial: SpatialResponse,
    grain: GrainModel,
    halation: HalationModel,
    sourceRefs: List<String>,
    evidenceByField: Map<String, EvidenceLevel>
)

data class RgbDensityResponse(
    exposureMinEv: Float,
    exposureMaxEv: Float,
    redLut: FloatArray,
    greenLut: FloatArray,
    blueLut: FloatArray
)

data class NegativeColorModel(
    toneGamma: Float,
    exposureBiasRgbEv: Vec3,
    layerMixMatrix: Mat3,
    saturationScale: Float
)

data class SpatialResponse(
    referenceWidthPx: Int,
    lowPassSigmaPx: Float,
    acutanceAmount: Float,
    acutanceRadiusPx: Float
)

data class GrainModel(
    amount: Float,
    radiusPxAtReferenceWidth: Float,
    irregularity: Float,
    chromaFraction: Float,
    shadowBias: Float
)

data class HalationModel(
    amount: Float,
    threshold: Float,
    radiusPxAtReferenceWidth: Float,
    redWeight: Float
)
```

约束：

- LUT 每通道固定 256 点，覆盖 `[-6 EV, +8 EV]`。
- 三条响应 LUT 必须有限、连续且单调非递减。
- `layerMixMatrix` 每行元素和应接近 1，禁止产生负亮度。
- 所有空间参数以 1080 px 输出宽度为参考，实际渲染按宽度同比缩放。
- `profileVersion` 在任何视觉参数变化时递增。
- 每个字段记录 `OFFICIAL`、`DIGITIZED`、`MEASURED` 或 `ESTIMATED`。

## 7. 算法管线

```text
输入 sRGB
  -> 线性化
  -> 场景曝光重建
  -> 乳剂内高光散射
  -> 感色层曝光与串扰
  -> R/G/B H&D 密度响应
  -> 密度域颗粒
  -> 乳剂空间响应
  -> 中性数字反相
  -> 固定显示映射
  -> sRGB 输出
```

### 7.1 输入线性化与曝光

输入仍为 CameraX 同帧冻结 Bitmap。首先按标准 sRGB EOTF 解码：

$$
C_{lin} =
\begin{cases}
C_{srgb}/12.92, & C_{srgb} \le 0.04045 \\
((C_{srgb}+0.055)/1.055)^{2.4}, & \text{otherwise}
\end{cases}
$$

沿用现有逐像素曝光落点：

$$
\Delta EV =
EV_{pixel,100} - EV_{preset,100}
$$

以 18% 灰为参考，将源像素转换为相对曝光：

$$
E_{rgb} =
\frac{RGB_{lin}}{\max(Y_{source},\epsilon)}
\cdot 0.18 \cdot 2^{\Delta EV}
$$

这一步保留源图色度，但亮度由同帧测光和固定相机参数决定。

### 7.2 感色层曝光

V1 不执行逐波长光谱积分，采用 3x3 层混合矩阵近似光谱重叠：

$$
E_{layer} =
M_{layer} \cdot E_{rgb} \odot 2^{b_{rgb}}
$$

- $b_{rgb}$：三层曝光偏移，单位 EV。
- $M_{layer}$：弱串扰矩阵。
- 计算结果限制为非负有限值。

首版矩阵从接近单位矩阵开始，非对角元素总量不超过 8%。真实光源依赖效果留给
后续基于 RAW 和光谱重建的版本。

### 7.3 H&D 密度响应

对每个颜色层：

$$
x_c = \log_2(\max(E_c,\epsilon)/0.18)
$$

$$
D_c = LUT_c(x_c)
$$

正式 profile 应由厂商 H&D 曲线数字化后执行单调重采样。数据尚未数字化时，
使用参数化 S 曲线生成 LUT：

1. 趾部：在暗部宽容度内从 $D_{min}$ 平滑进入直线段。
2. 直线段：斜率由 `gamma` 控制。
3. 肩部：在高光宽容度内逐渐趋近 $D_{max}$。
4. 宽容度外继续保留 1 EV 软延伸，避免数值硬截断。

不得使用可能过冲的普通 cubic spline。数字化曲线采用 PCHIP 或分段
monotonic Hermite 插值。

### 7.4 颜色形成

逐通道密度响应已经产生曝光相关色偏。随后仅应用弱层间混合和一个受限饱和度
系数：

$$
D' = M_{color} \cdot D
$$

`saturationScale` 只补偿 V1 缺失的完整染料吸收与 coupler 模型，范围限制为
`[0.85, 1.15]`。禁止用任意 HSL 色相旋转作为胶片核心特征。

### 7.5 颗粒

颗粒在密度域生成，而不是在最终 sRGB 图上叠加白噪声：

$$
q_c = clamp\left(\frac{D_c-D_{min,c}}{D_{max,c}-D_{min,c}},0,1\right)
$$

基础密度相关幅度：

$$
A(q)=A_0\left(0.2+4q(1-q)\right)
$$

该函数使颗粒在中间密度最明显，在密度两端减弱。`shadowBias` 可以把峰值轻微
移动到中性反相后的暗部，但首版偏移不得超过归一化密度的 0.15。

随机场由两部分组成：

```text
grain =
  (1 - chromaFraction) * sharedLumaNoise
  + chromaFraction * independentLayerNoise
```

实现要求：

- 使用确定性 32-bit seed：`hash(frameToken, filmProfileId, profileVersion)`。
- 使用 2 至 3 个带限噪声 octave，形成不规则团簇；禁止逐像素独立白噪声。
- 在密度域相加后再反相，颗粒会自然受到局部曝光影响。
- 实时预览允许两个 octave；冻结成片使用三个 octave。
- 颗粒尺度跟随模拟底片物理尺寸和输出放大率，而不是固定屏幕像素。
- V1 当前相机预设均按 135 底片处理。

厂商 RMS granularity 或 Print Grain Index 只用于 profile 之间的相对强度排序。
RMS 数值不能直接作为像素标准差，PGI 也不能与 RMS 数值直接比较。

### 7.6 空间响应

MTF 首版用“轻微低通 + 弱中频 acutance”近似：

$$
I_{soft}=Gaussian(I,\sigma)
$$

$$
I_{spatial}=I_{soft}+a(I_{soft}-Gaussian(I_{soft},r))
$$

- `lowPassSigmaPx` 表示乳剂和染料云造成的高频衰减。
- `acutanceAmount` 表示显影邻接效应的近似；由于 V1 不模拟显影，默认值必须
  很小，只用于匹配厂商 MTF 的中频形状。
- 计算在线性正像或归一化密度上进行，不在 gamma 编码后的 sRGB 上锐化。

### 7.7 Halation

Halation 属于光在乳剂与片基层内传播的结果，可视为负片自身特性，因此保留一个
克制的可选模型：

1. 从 scene-linear 输入提取超过 `threshold` 的高光能量。
2. 使用宽半径高斯核扩散。
3. 以红色权重重新注入曝光层，再执行 H&D 响应。

$$
H = blur(max(Y-threshold,0),r)
$$

$$
E'_{rgb}=E_{rgb}+amount \cdot H \cdot (w_r,w_g,w_b)
$$

普通 C-41 静态负片具有防光晕层，V1 强度上限为 0.03，不能做成明显红色光圈。

### 7.8 中性数字反相与显示

对颗粒化后的密度执行固定归一化：

$$
P_c =
clamp\left(
\frac{D'_c-D_{min,c}}{D_{gray,c}-D_{min,c}}
\cdot P_{gray},
0,P_{max}
\right)
$$

其中 `P_gray = 0.18`。随后执行固定高光 roll-off、统一白点适配和 sRGB OETF。
这些参数在所有胶片之间完全相同，因此不引入扫描仪或实验室风格。

## 8. V1 内置胶片参数

### 8.1 参数口径

以下是首版**工程初值**：

- `gamma RGB`：参数化 H&D 直线段斜率。
- `tone`：独立于 RGB 通道差异的整体中间调反差。
- `bias RGB`：三层曝光偏移，单位 EV。
- `matrix`：保持中性灰的 3×3 颜色交叉矩阵。
- `sat`：中性反相后的受限饱和度补偿。
- `grain`：相对颗粒量。
- `radius`：1080 px 宽、135 底片输出下的颗粒参考半径。
- `soft`：乳剂低通高斯 sigma，单位参考像素。
- `halation`：乳剂内高光散射强度。

除 ISO 和既有宽容度外，下表视觉参数均标记为 `ESTIMATED`，必须通过测试样片
验证后才能提升证据等级。

### 8.2 当前预设映射

本节只覆盖 `filmpreview/DisposableCameraPreset.kt` 中当前实际可选择或内置的彩色
负片。专业测光页的 `FilmLatitudePreset` 只提供风险阈值，不是成像 profile；
其中 Vision3、Lucky 和 Ektachrome 等预设不在本次实现范围。即时成像相纸也由
独立模块负责。

| Profile ID | 当前显示名称/用途 | ISO | 暗部 / 高光 | tone | gamma RGB | bias RGB EV | sat | grain | radius |
| --- | --- | ---: | ---: | ---: | --- | --- | ---: | ---: | ---: |
| `generic-color-100` | 通用 ISO 100 彩色负片 | 100 | -2 / +3 | 0.98 | 0.64 / 0.64 / 0.64 | 0 / 0 / 0 | 1.00 | 0.50 | 1.80 |
| `kodak-gold-200` | Kodak Gold 200 | 200 | -2 / +3 | 0.94 | 0.62 / 0.64 / 0.67 | +0.06 / 0 / -0.08 | 1.12 | 0.70 | 2.20 |
| `kodak-ultra-max-400` | Kodak Ultra Max 400 | 400 | -2 / +3 | 1.03 | 0.65 / 0.67 / 0.69 | +0.04 / 0 / -0.04 | 1.14 | 0.90 | 2.80 |
| `generic-color-800` | 通用 ISO 800 彩色负片 | 800 | -2 / +3 | 0.95 | 0.60 / 0.61 / 0.63 | 0 / 0 / 0 | 0.94 | 1.25 | 3.60 |
| `kodak-disposable-800` | FunSaver / Power Flash 内置 Kodak ISO 800 | 800 | -2 / +3 | 1.00 | 0.61 / 0.63 / 0.66 | +0.05 / 0 / -0.07 | 1.10 | 1.30 | 3.80 |
| `fuji-superia-xtra-400` | FUJICOLOR SUPERIA X-TRA 400 | 400 | -1.67 / +3 | 1.02 | 0.65 / 0.64 / 0.66 | -0.03 / +0.03 / +0.02 | 1.12 | 0.90 | 2.60 |
| `fuji-c400-400` | Fujifilm C400 ISO 400 彩色负片 | 400 | -2 / +3 | 1.00 | 0.63 / 0.64 / 0.66 | 0 / +0.02 / 0 | 1.08 | 0.95 | 2.80 |

补充固定值：

| 参数 | V1 值 |
| --- | ---: |
| LUT 曝光范围 | -6 EV 至 +8 EV |
| LUT 采样数 | 每通道 256 |
| `D_min RGB` | 0 / 0 / 0 的相对密度；忽略橙色片基常量 |
| `D_max` | 按各通道曲线归一化到 1 |
| 串扰矩阵非对角总量 | 不超过 8% |
| `chromaFraction` ISO 100/200 | 0.12 |
| `chromaFraction` ISO 400 | 0.16 |
| `chromaFraction` ISO 800 | 0.20 |
| `irregularity` | 0.35 |
| `shadowBias` | 0.10 |
| 颗粒 EV 基础强度 | 0.16 |
| halation threshold | 线性显示映射前 0.80 |
| halation red weight RGB | 1.00 / 0.30 / 0.08 |
| halation radius | 1080 px 宽下 5 px |

### 8.3 预设身份修正

当前部分 `FilmProfile` 未显式传入 `id`，会退化为显示名称。实现 V1 时应显式使用
上表稳定 ID：

| 当前对象 | V1 稳定 ID |
| --- | --- |
| FunSaver / Power Flash 内置 ISO 800 | `kodak-disposable-800` |
| QuickSnap 内置 Superia X-TRA 400 | `fuji-superia-xtra-400` |
| C400 内置 ISO 400 | `fuji-c400-400` |

持久化层应兼容旧名称值并迁移到稳定 ID。

## 9. 数据文件建议

profile 不应长期硬编码在 Kotlin/Swift 枚举中。建议使用版本化 JSON：

```json
{
  "schemaVersion": 1,
  "profileVersion": 1,
  "id": "kodak-gold-200",
  "nominalIso": 200,
  "latitude": {
    "shadowStops": 2.0,
    "highlightStops": 3.0
  },
  "response": {
    "exposureMinEv": -6.0,
    "exposureMaxEv": 8.0,
    "redLutAsset": "curves/kodak-gold-200-r.f32",
    "greenLutAsset": "curves/kodak-gold-200-g.f32",
    "blueLutAsset": "curves/kodak-gold-200-b.f32"
  },
  "grain": {
    "amount": 0.58,
    "radiusPxAt1080": 0.85,
    "irregularity": 0.35,
    "chromaFraction": 0.12,
    "shadowBias": 0.10
  }
}
```

要求：

- JSON 保存参数和来源；二进制 LUT 保存密集采样。
- App 启动时校验 schema、数值范围、LUT 长度和单调性。
- profile 加载失败时回退 `generic-color-{nearestIso}`。
- Android 和 iOS 消费同一份 profile 资产并运行共享测试向量。

## 10. GPU 与 CPU 实现

### 10.1 实时 GPU

建议按三个 pass 实现：

```text
Pass 1: scene-linear exposure + halation mask
Pass 2: H&D LUT + density grain + spatial response
Pass 3: neutral inversion + display encoding
```

低端设备降级顺序：

1. 关闭 halation。
2. 颗粒从三个 octave 降为两个。
3. 关闭 acutance，只保留轻微低通。
4. 将模拟渲染尺寸降低到视图尺寸。

不得降级逐通道响应曲线，也不得改动曝光参考。

### 10.2 CPU 回退

- sRGB EOTF/OETF 继续使用 LUT。
- H&D 每通道使用 256 点 LUT 线性插值。
- 噪声使用坐标哈希和可分离低通，不创建每像素对象。
- 冻结成片允许跳过 halation 和 acutance，但必须保留逐通道曲线及确定性颗粒。
- CPU 和 GPU 的纯色块结果每通道误差不超过 `2/255`。

## 11. 测试与验收

### 11.1 数学测试

- 每条 H&D LUT 有 256 点、数值有限、单调非递减。
- `Delta EV = 0` 的中性灰输出亮度为 `0.18 +/- 0.01`。
- 输入曝光从 -6 EV 扫描到 +8 EV 时输出无反转和突跳。
- 所有矩阵输入输出有限且不产生负曝光。
- 同一 seed、尺寸和 profile 输出逐像素一致。
- 不同 seed 的颗粒均值差小于 1%，但空间图案不同。

### 11.2 视觉测试图

每个 profile 固定生成：

1. `-6 EV ... +8 EV`、步长 `1/3 EV` 的中性灰阶。
2. 24 色 ColorChecker，在 `-2 / 0 / +2 EV` 下的结果。
3. 肤色、蓝天、绿色植物、红色高饱和物的标准图。
4. 纯色平场，用于测量颗粒均值、方差和频谱。
5. 黑白斜边和 Siemens star，用于检查 MTF 与 halo。

### 11.3 产品验收

- 切换胶片不会改变相机固定曝光计算和风险判断。
- Kodak Gold 200 相比通用 ISO 100 应更暖、饱和度略高、颗粒更明显。
- ISO 800 profile 相比 ISO 100 有更粗、更强的颗粒和更低的细节响应。
- Superia X-TRA 400 与 Kodak 400 在灰阶上接近，但绿色/青色响应可辨认。
- 高光 roll-off 连续，不出现 RGB 单通道突然截断。
- 100% 查看时颗粒成团但无规则网格、固定贴图接缝或彩色热像素观感。
- 缩放、旋转和不同输出分辨率下颗粒物理尺度保持一致。

性能目标沿用现有文档：

| 场景 | 目标 |
| --- | ---: |
| 实时模拟 | 中高端设备 15-24 fps |
| 冻结图 GPU 渲染 | P95 不超过 250 ms |
| 冻结图完整生成 | P95 不超过 1.5 s |
| 主线程单帧工作 | 不超过 8 ms |

## 12. 标定计划

V1 工程初值上线前至少完成以下标定：

1. 对能购得的每款底片拍摄 18% 灰卡和 ColorChecker。
2. 固定镜头、光源、色温和显影流程，执行 `-3 EV` 至 `+5 EV` 包围曝光。
3. 使用同一设备获取尽量接近原始的 16-bit 线性扫描。
4. 单独扫描未曝光已显影片基，估计 D-min 和橙色片基。
5. 用灰阶拟合 H&D 曲线，用色卡拟合弱层混合矩阵。
6. 用均匀曝光区域测量不同密度下的颗粒方差和二维功率谱。
7. 用斜边图拟合近似 MTF。

冲洗与扫描虽然是测量手段，但标定时应固定并尽量线性化；不得把扫描仪自动
色彩和锐化直接写入胶片 profile。无法分离的残差必须继续标记为 `MEASURED`
而不是 `OFFICIAL`。

## 13. 实施顺序

1. 将现有 `FilmProfile` 拆分为曝光宽容度与成像 profile，并补齐稳定 ID。
2. 实现参数化逐通道 H&D LUT，先保持颗粒、MTF、halation 关闭。
3. 改造 GPU 与 CPU 渲染器，完成跨后端纯色块一致性测试。
4. 在密度域加入确定性、多尺度颗粒。
5. 加入轻量空间响应和可关闭 halation。
6. 生成全部 profile 的标准测试图并人工验收。
7. 用真实底片标定数据逐项替换 `ESTIMATED` 参数。

首版发布门槛是逐通道响应和密度相关颗粒稳定可用；MTF 与 halation 若未达到
性能或视觉标准，可以默认关闭，但不得用固定滤镜或静态颗粒贴图替代。
