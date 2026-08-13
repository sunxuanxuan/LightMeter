# 冻结画面曝光风险预览设计

## 1. 目标

用户点击定格按钮后，在冻结画面上显示当前胶片 ISO、光圈和快门组合对应的曝光风险：

- 高光超出配置宽容度时显示半透明红色。
- 暗部低于配置宽容度时显示半透明绿色。
- 正常区域保持原画面。
- 只分析当前模拟取景框内的像素。

该功能用于提示曝光风险，不等同于特定胶片的精确特性曲线模拟。

## 2. 坐标一致性

`Preview` 和 `ImageAnalysis` 必须使用同一个 CameraX `ViewPort`：

```text
PreviewView.viewPort
    -> UseCaseGroup
        -> Preview
        -> ImageAnalysis
```

分析器只读取 `ImageProxy.cropRect` 内的像素。裁剪区域经过
`ImageInfo.rotationDegrees` 旋转后，直接归一化到 PreviewView 的
`0.0 ~ 1.0` 坐标，不再根据两条流的分辨率手工模拟二次裁剪。

## 3. 逐像素 EV

冻结前，分析器保留最近一帧中等分辨率逐像素 EV 图。长边限制为 480
像素。每个单元读取源图对应区域的四个四分位采样点，使用线性亮度均值计算
EV；至少 25% 采样点到达裁切阈值时标记高光裁切。这样能降低中心单点采样对
细小高光和边缘的漏检，同时保持实时分析开销可控。

逐像素图、该帧未平滑区域 EV、时间戳和配置 revision 组成同一个原子
`ExposureSnapshot`。冻结风险只使用该快照，实时 UI 的历史平滑 EV 不参与
风险基线。

手机当前曝光参数对应：

$$
EV_{setting,100}
=
\log_2\left(\frac{N_{phone}^2}{t_{phone}}\right)
-
\log_2\left(\frac{ISO_{phone}}{100}\right)
$$

Y 平面像素先转换为线性亮度：

$$
Y_{linear}
=
\left(\frac{Y}{255}\right)^{2.2}
$$

逐像素场景 EV：

$$
EV_{pixel,100}
=
EV_{setting,100}
+
\log_2\left(\frac{Y_{linear}}{0.18}\right)
+
calibrationOffset
$$

## 4. 风险判定

冻结后，当前曝光补偿对应的参考场景 EV100：

$$
EV_{reference,100}
=
EV_{metered,100}-EC
$$

该式等价于先计算
$EV_{target}=EV_{metered,100}+\log_2(ISO/100)-EC$，再减去胶片 ISO
项。直接使用连续的目标 EV，可以避免光圈和快门离散档位取整导致 `0.3 EV`
补偿偶尔不改变风险蒙层。

像素相对中灰的位置：

$$
\Delta EV = EV_{pixel,100} - EV_{reference,100}
$$

判定规则：

```text
round10(abs(deltaEV)) <= round10(latitude) -> 正常
deltaEV > 0 且超出高光边界                 -> 高光风险
deltaEV < 0 且超出暗部边界                 -> 暗部风险
```

宽容度设置和预设仍量化到 `1/3 EV`，风险比较量化到 `0.1 EV`。边界值本身
不报警，例如暗部边界 `-1.7 EV`、像素差值 `-1.8 EV` 时显示暗部风险。

亮度阈值只说明区域相对中灰过亮或过暗，无法区分固有纯白/纯黑物体与曝光
造成的细节丢失。冻结检测采用单帧保守判定，不修改手机相机曝光。

8-bit YUV 经过 ISP 和 tone mapping 后无法表示胶片 `+3 ~ +6 EV` 的完整
高光范围。若 Y 值达到视频范围白端 `235`，说明预览信号已无法继续量化高光，
即使计算出的 $\Delta EV$ 尚未达到胶片阈值，也显示深红色保守预警。该标记
表示“手机预览高光已裁切”，不等价于胶片一定溢出。

默认配置：

```text
highlightLatitude = 4.0 EV
shadowLatitude = 3.0 EV
adjustmentStep = 1/3 EV
comparisonStep = 0.1 EV
range = 1/3 EV ~ 8.0 EV
```

### 4.1 单帧保守判定

冻结时由同一个 `ImageProxy` 原子生成正常曝光 Bitmap 和
`ExposureSnapshot`，随后直接计算风险图和模拟曝光：

```text
正常曝光单帧
-> 逐像素 EV 与胶片宽容度比较
-> YUV 高光裁切检查
-> 风险计算和模拟渲染
```

高光超过正宽容度或达到 YUV 裁切阈值时显示红色；暗部低于负宽容度时显示
绿色。算法不会生成模拟 `+2 EV/-2 EV` 帧，因为对 8-bit YUV 做数值增减曝光
无法恢复已经裁切或量化丢失的纹理。

单帧方案消除了曝光切换、帧间位移和探测等待，但无法区分固有纯白/纯黑物体
与曝光导致的细节丢失。因此风险图采用保守语义：颜色表示该区域超过所选胶片
宽容度或手机信号已经裁切，不表示算法确认该区域存在可恢复纹理。

### 4.2 性能

冻结帧最多等待 `600 ms`。取得单帧后立即提交 Bitmap 和 ExposureSnapshot；
风险图计算运行在后台调度器，Compose 主线程只负责创建和显示最终 Bitmap。

### 4.3 渐进风险强度

越过宽容度阈值后，根据继续超出的 EV 调整红色或绿色蒙层的 Alpha：

$$
excess_{highlight}=\max(0,\Delta EV-highlightLatitude)
$$

$$
excess_{shadow}=\max(0,-\Delta EV-shadowLatitude)
$$

$$
intensity=clamp\left(\frac{excess}{2.0},0,1\right)
$$

Alpha 从首次越过边界的 `0.1 EV` 档位开始增加，到超出 2 EV 时达到 `0xE6`
（约 90%），之后保持封顶。这样轻微越界仍能看到原画面，严重越界则得到更
扎实的警示色。高光/暗部风险百分比仍按是否越过阈值统计，不受 Alpha 变化影响。

### 4.4 胶片预设

预设值必须使用本功能的同一口径：相对于 18% 灰参考曝光，像素还能向
高光或暗部偏移多少档。厂商所说的“可过曝/欠曝几档”是整张照片的曝光
误差容忍度，不能直接作为这里的逐像素阈值。

| 预设 | 高光 | 暗部 | 依据 |
| --- | ---: | ---: | --- |
| 柯达 Gold 200 | +3.0 EV | -2.0 EV | 官方资料的实用外侧边界 |
| 柯达 UltraMax 400 | +3.0 EV | -2.0 EV | 同类消费彩负保守近似 |
| 富士 200（现行版） | +3.0 EV | -2.0 EV | 按 Gold 200 使用逻辑 |
| 富士 400（现行版） | +3.0 EV | -2.0 EV | 按 UltraMax 400 使用逻辑 |
| 富士 C200 / Superia 400（旧日版） | +3.0 EV | -1.7 EV | 批次差异保守近似 |
| 乐凯 C200 | +2.7 EV | -1.7 EV | 实用范围外侧取 1/3 档 |
| 乐凯 C400 | +2.7 EV | -1.7 EV | 资料不足的暂定估算 |
| 柯达 VISION3 250D 5207 | +4.7 EV | -3.0 EV | 实用范围外侧取 1/3 档 |
| 柯达 VISION3 50D 5203 | +5.0 EV | -3.0 EV | 官方曲线支持的实用范围 |
| 柯达 E100 | +0.7 EV | -1.7 EV | 反转片保守实用范围 |
| 柯达 5294 / 7294 100D | +0.7 EV | -1.7 EV | 反转片保守实用范围 |

预设以[主流胶片曝光宽容度参考表](../shared/film-exposure-latitude-reference.md)的
“建议实用范围”外侧边界为依据，并量化到最近的 `1/3 EV`，不使用更激进的
“极限可用参考”。

资料来源：

- [Kodak VISION3 250D 5207/7207 官方 brochure](https://www.kodak.com/content/products-brochures/motion-picture/KODAK-VISION3-250D-5207-7207-brochure.pdf)。
- [Kodak VISION3 50D 5203/7203 官方 brochure](https://www.kodak.com/content/pdfs/motion/KODAK-VISION3-50D-5203-7203-brochure.pdf)。
- [Kodak EKTACHROME 100D 5294/7294 官方资料](https://www.kodak.com/content/products-brochures/Film/KODAK-EKTACHROME-100D-COLOR-REVERSAL-FILM-5294-7294-datasheet-EN.pdf)。
- [Kodak EKTACHROME E100 官方资料](https://www.bhphotovideo.com/lit_files/519170.pdf)。
- [Fujicolor C200 官方 Product Information Bulletin](https://asset.fujifilm.com/www/us/files/2025-06/79a1ad75d6ce087912eb7f63bfebb1f1/films_c200_datasheet_01.pdf)。
- [Fujifilm 400 官方 Product Information Bulletin](https://static.bhphoto.com/lit_files/1152748.pdf)。
- [Kodak UltraMax 400 官方 Technical Data](https://business.kodakmoments.com/sites/default/files/files/resources/E7023_max_400.pdf)。
- [Kodak Gold 200 官方 Technical Data](https://kodakprofessional.com/sites/default/files/wysiwyg/pro/resources/E7022%20Gold%20tech%20sheet.pdf)。
- [乐凯 C400 2026 年首发信息](https://www.ea360.com/contents/23/39973.html)。

## 5. UI

风险蒙层位于冻结 Bitmap 上方、取景框遮罩下方。取景框外保持原有暗色
遮罩。画面顶部显示：

```text
高光风险百分比
暗部风险百分比
```

当用户在冻结状态调整曝光补偿时，保留冻结画面和同一逐像素 EV 图，仅用
新的 $EV_{reference,100}$ 重新计算蒙层，不重新读取相机帧。正曝光补偿
降低参考 EV，高光风险增加、暗部风险减少；负曝光补偿则相反。顶部风险图例
同步显示当前 EC。

## 6. 精度边界

- YUV 数据仍可能受到手机 ISP、HDR 和局部 tone mapping 影响。
- 固有黑色与欠曝、固有纯白与高光裁切在单帧信号中可能完全相同，因此会产生
  保守误报，算法不能理解摄影者的明暗表达意图。
- YUV 饱和预警是保守兜底，无法恢复已裁切高光的真实超出档数。
- 除 VISION3 官方曲线预设外，其余值是保守风险近似，不代表厂商保证值。
- 冲洗配方、扫描仪动态范围、扫描操作和后期处理都会改变最终可用细节。
- PreviewView Bitmap 与最近分析帧存在很小的时间差，但二者共享相同
  ViewPort，因此空间坐标保持一致。
