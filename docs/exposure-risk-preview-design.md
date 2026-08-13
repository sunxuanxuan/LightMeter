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

冻结前，分析器保留最近一帧低分辨率逐像素 EV 图。长边限制为 240
像素，避免实时分析产生明显负担。

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
deltaEV >= highlightLatitude -> 高光风险
deltaEV <= -shadowLatitude   -> 暗部风险
otherwise                    -> 正常
```

亮度阈值只说明区域相对中灰过亮或过暗，无法区分固有纯白/纯黑物体与曝光
造成的细节丢失。因此冻结时会按需采集增曝光或减曝光探测帧，进一步筛选风险。

8-bit YUV 经过 ISP 和 tone mapping 后无法表示胶片 `+3 ~ +6 EV` 的完整
高光范围。若 Y 值达到视频范围白端 `235`，说明预览信号已无法继续量化高光，
即使计算出的 $\Delta EV$ 尚未达到胶片阈值，也进入高光候选区域。若设备
无法提供有效减曝光探测帧，则回退为深红色保守预警。该标记表示“手机预览
高光已裁切”，不等价于胶片一定溢出。

默认配置：

```text
highlightLatitude = 4.0 EV
shadowLatitude = 3.0 EV
adjustmentStep = 1/3 EV
range = 1/3 EV ~ 8.0 EV
```

### 4.1 明暗细节探测帧

冻结时先保留正常曝光 Bitmap 和同帧 `ExposureSnapshot`，再通过 CameraX
曝光补偿接口按候选区域请求探测帧：

```text
存在暗部候选 -> 约 +2 EV
存在高光候选 -> 约 -2 EV
```

没有对应候选时跳过该方向。实际补偿量按设备支持的步长和上下限裁剪；可用
增量不足 `1 EV` 时放弃该方向。只有 Camera2 元数据显示相机曝光参数已经
发生对应变化后，新的 `ExposureSnapshot` 才可采用。两种探测共享同一个
包围曝光会话，结束、失败或用户取消冻结时只恢复一次原曝光补偿。

正常帧中低于暗部宽容度阈值的像素只是候选区域。以像素为中心读取两张
低分辨率 Y 图的 `5×5` 邻域，并计算平均亮度和相邻像素平均梯度。仅在以下
条件同时成立时显示暗部风险：

```text
探测帧平均亮度明显提高
正常帧局部梯度低，尚无可见结构
探测帧局部梯度明显提高并超过细节阈值
探测帧没有接近高光饱和
```

若黑色头发、背包等区域在增曝光后仍保持平坦，则不显示暗部风险。若正常帧
已经包含清晰纹理，也不属于“因曝光不足而隐藏的细节”，不会提示。设备不支持
曝光补偿、探测超时或两帧尺寸不一致时，回退到原有宽容度阈值算法。

该方法判断的是增曝光后是否出现可测的局部结构，仍不能推断摄影者是否希望
某个黑色主体保持纯黑。

高光采用对称规则：正常帧高于宽容度或 Y 值裁切的区域先作为候选；若
`-2 EV` 探测帧明显变暗且出现正常帧中没有的局部纹理，则提示高光细节风险。
纯白墙面、白纸等在减曝光后仍保持平坦的区域不提示。摄影者是否希望白色区域
保持纯白同样属于表达意图，算法不作语义推断。

### 4.2 性能

探测采集最多等待 `800 ms`，并先扫描正常曝光图决定需要哪个方向，避免无风险
区域仍触发相机曝光切换。明暗候选同时存在时，从原始曝光直接切换到两个目标
补偿值，结束后统一恢复。

局部均值和梯度使用二维积分图计算。每张曝光图只做一次 $O(W H)$ 前缀和，
每个 `5×5` 邻域随后用常数次查询得到统计量，避免逐风险像素重复扫描邻域。
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

Alpha 从阈值处的 `0x33`（约 20%）线性增加到超出 2 EV 时的 `0xE6`
（约 90%），之后保持封顶。这样轻微越界仍能看到原画面，严重越界则得到
更扎实的警示色。高光/暗部风险百分比仍按是否越过阈值统计，不受 Alpha
变化影响。

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

预设以 [主流胶片曝光宽容度参考表](film_exposure_latitude_reference.md) 的
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
- 探测帧与正常帧存在短时间差，手持晃动或运动主体可能影响局部纹理比较。
- 固有黑色与欠曝在物理信号上可能完全相同，探测算法只能降低误报，不能理解
  摄影者的明暗表达意图。
- 固有纯白与高光裁切也存在同样的信号歧义，减曝光探测只能判断是否出现新的
  局部结构。
- YUV 饱和预警是保守兜底，无法恢复已裁切高光的真实超出档数。
- 除 VISION3 官方曲线预设外，其余值是保守风险近似，不代表厂商保证值。
- 冲洗配方、扫描仪动态范围、扫描操作和后期处理都会改变最终可用细节。
- PreviewView Bitmap 与最近分析帧存在很小的时间差，但二者共享相同
  ViewPort，因此空间坐标保持一致。
