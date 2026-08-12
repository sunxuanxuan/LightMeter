# 测光算法设计

## 1. 目标

本设计用于 Android 胶片测光 App V1，目标是在实时相机预览中估算当前场景的合理 `EV100`，并结合用户设定的胶片 `ISO`、曝光补偿、1/3 档光圈表和经典整档快门表，生成一组主推荐曝光组合以及多组等效曝光组合。

V1 支持两种测光模式：

- 平均测光：默认模式，对当前预览画面整体测光。
- 点击区域测光：用户点击画面后，以点击点为中心，使用固定圆形区域测光。

## 2. 核心约束

不能只依赖预览画面的明暗直接计算绝对 EV。手机相机会自动调节曝光时间、ISO、增益、HDR、降噪和 tone mapping，预览画面的像素值不是场景真实亮度。

因此 V1 的核心思路是：

```text
用预览画面亮度 + 手机相机当前曝光参数，反推当前场景 EV。
```

需要从 CameraX / Camera2 获取：

- `SENSOR_EXPOSURE_TIME`：手机当前曝光时间，单位 ns。
- `SENSOR_SENSITIVITY`：手机当前 ISO。
- `CONTROL_POST_RAW_SENSITIVITY_BOOST`：RAW 后额外数字增益，缺失时按 100%。
- `LENS_APERTURE`：手机镜头光圈值，通常为固定值，例如 f/1.8。
- `ImageAnalysis` 预览帧的 YUV 数据，主要使用 Y 平面。

## 3. EV 估算公式

先计算手机当前曝光设置对应的 EV：

```text
ISO_effective = ISO_camera * post_raw_boost / 100
EV_setting = log2(N^2 / t) - log2(ISO_effective / 100)
```

其中：

- `N`：手机镜头光圈值。
- `t`：手机当前曝光时间，单位秒。
- `ISO_camera`：手机当前传感器 ISO。

然后根据预览画面测得亮度修正：

```text
EV100 = EV_setting + log2(Y_measured / Y_target) + calibration_offset
```

其中：

- `Y_measured`：测光区域内的线性亮度平均值。
- `Y_target`：正常曝光目标亮度，V1 初始取 `0.18`。
- `calibration_offset`：设备校准偏移，V1 默认 `0EV`。

如果画面比目标中灰更亮，`Y_measured / Y_target > 1`，估算出的场景 EV 更高；如果画面更暗，则 EV 更低。

## 4. 亮度计算

CameraX `ImageAnalysis` 可获取 `ImageProxy`，从中读取 YUV 的 Y 平面。

Y 平面像素通常不能视为严格线性光强，V1 使用 gamma 反变换做近似：

```text
Y_norm = Y / 255.0
Y_linear = pow(Y_norm, 2.2)
```

测光亮度为测光区域内 `Y_linear` 的裁剪平均值。

为降低极端高光和阴影对测光结果的影响，V1 使用 trimmed mean：

```text
1. 收集测光区域内所有 Y_linear。
2. 去掉最暗 5%。
3. 去掉最亮 5%。
4. 对剩余像素求平均，得到 Y_measured。
```

## 5. 测光区域

### 5.1 平均测光

平均测光使用模拟取景框内部：

```text
ROI = simulated viewfinder frame
Y_measured = trimmedMean(Y_linear over ROI)
```

### 5.2 点击区域测光

用户点击画面后，以点击点为圆心创建圆形区域，且只统计模拟取景框内部。

V1 规则：

```text
circle_area = viewfinder_area * spot_area_percent
circle_radius = sqrt(circle_area / PI)
```

测光区域：

```text
ROI = pixels where distance(pixel, touch_point) <= circle_radius
Y_measured = trimmedMean(Y_linear over ROI)
```

点击点来自 UI 坐标，需要转换到 `ImageAnalysis` 图像坐标。转换时需要处理：

- 预览画面与分析帧的宽高比例差异。
- 预览裁剪或 letterbox。
- 前后摄像头镜像。
- 图像旋转角度。

V1 只使用后置摄像头时，可以先固定后置摄像头路径，降低坐标转换复杂度。

## 6. 时间平滑

实时测光会抖动，需要对 EV 做时间平滑。

默认平滑：

```text
EV_smooth = EV_previous * 0.75 + EV_current * 0.25
```

点击测光后，为了让结果更快响应，可以短时间使用更高的新值权重：

```text
EV_smooth = EV_previous * 0.4 + EV_current * 0.6
```

当测光模式切换、ISO 切换、曝光补偿切换时，不需要重置测光值；当点击区域发生变化时，可以在 300ms 到 500ms 内使用快速平滑系数。

## 7. 曝光补偿

测光算法输出的是正常曝光场景 EV：

```text
EV100_metered
```

用户设置曝光补偿 `EC` 后，用于生成推荐曝光组合的 EV 为：

```text
EV100_final = EV100_metered - EC
```

这里使用减号：

- `+1EV` 补偿表示用户希望照片更亮，需要增加曝光量，因此推荐更慢快门或更大光圈，对应更低的曝光 EV。
- `-1EV` 补偿表示用户希望照片更暗，需要减少曝光量，因此推荐更快快门或更小光圈，对应更高的曝光 EV。

V1 建议曝光补偿范围：

```text
-3EV ~ +3EV
```

步进：

```text
1/3EV
```

## 8. 胶片 ISO 换算

用户设定胶片 ISO 后，将 `EV100_final` 换算到当前胶片 ISO 下：

```text
EV_film = EV100_final + log2(ISO_film / 100)
```

例如：

- `ISO_film = 100` 时，`EV_film = EV100_final`。
- `ISO_film = 400` 时，`EV_film = EV100_final + 2`。

## 9. 曝光组合生成

曝光组合需要满足：

```text
EV_film ~= log2(N^2 / t)
```

其中：

- `N`：胶片机光圈值。
- `t`：胶片机快门时间，单位秒。

V1 使用 1/3 档光圈表和经典整档快门表，遍历所有组合，计算每组组合的 EV：

```text
EV_pair = log2(aperture^2 / shutter_seconds)
error = abs(EV_pair - EV_film)
```

保留误差小于阈值的组合：

```text
error <= 1/6EV
```

光圈使用 1/3 档，因此 `1/6EV` 是合理的最近档位匹配容差。

## 10. 主推荐组合选择

在所有等效曝光组合中选择一组主推荐。

V1 推荐使用安全快门优先：

```text
20mm ~ 35mm：优先快门不慢于 1/30s
36mm ~ 50mm：优先快门不慢于 1/60s
51mm ~ 90mm：优先快门不慢于 1/125s
91mm ~ 120mm：优先快门不慢于 1/250s
```

选择规则：

```text
1. 先筛选不慢于安全快门的组合。
2. 在筛选结果中优先选择常用光圈，例如 f/2.8、f/4、f/5.6、f/8、f/11。
3. 如果没有满足安全快门的组合，则选择误差最小且快门最快的组合。
4. 其余组合按快门从快到慢或光圈从大到小展示。
```

## 11. 推荐流程

完整流程：

```text
1. 启动 CameraX Preview + ImageAnalysis。
2. 从 ImageAnalysis 获取 YUV 预览帧。
3. 从 Camera2 CaptureResult 获取当前曝光参数：
   - exposureTime
   - sensorSensitivity
   - aperture
4. 根据测光模式确定 ROI：
   - 平均测光：全画面
   - 点击测光：点击点附近 10% 圆形区域
5. 对 ROI 内 Y 平面做 gamma 反变换。
6. 使用裁剪平均计算 Y_measured。
7. 使用手机曝光参数和 Y_measured 反推 EV100。
8. 对 EV100 做时间平滑。
9. 应用曝光补偿，得到 EV100_final。
10. 根据胶片 ISO 换算 EV_film。
11. 遍历经典整档快门和 1/3 档光圈表，生成等效曝光组合。
12. 根据焦段和画幅选择主推荐组合。
13. UI 展示：
    - EV100_metered
    - EV100_final
    - 主推荐快门 / 光圈
    - 多组等效曝光组合
```

## 12. 精度边界

V1 可以达到实用级测光，但不能保证专业测光表级精度。

主要误差来源：

- 手机厂商预览 tone mapping。
- HDR、夜景、自动增强、局部对比度处理。
- 手机镜头实际 T-stop 与标称 F-stop 的差异。
- CameraX 图像帧与 Camera2 元数据可能不是严格同一帧。
- 高反差场景下平均测光天然会受极端区域影响。
- 不同设备的 YUV 输出和 ISP 行为存在差异。

## 13. 校准设计

V1 建议预留设备校准项：

```text
calibration_offset = -3.0EV ~ +3.0EV
step = 1/3EV
default = 0EV
```

用户可以通过灰卡或专业测光表校准：

```text
1. 用手机 App 对准标准 18% 灰卡。
2. 使用专业测光表或可信相机得到目标 EV100。
3. 调整 calibration_offset，使 App 显示 EV100 接近目标值。
4. 将 calibration_offset 保存在本地设置中。
```

## 14. V1 实现建议

Android 技术组件：

- CameraX `Preview`：实时取景。
- CameraX `ImageAnalysis`：获取 YUV 帧并计算亮度。
- Camera2 interop：读取 `CaptureResult` 中的曝光时间、ISO 和光圈。
- Jetpack Compose：展示 EV、ISO、曝光补偿、主推荐组合和等效组合。

实现时建议将算法拆成独立模块：

```text
MeteringAnalyzer
ExposureMetadataProvider
EvCalculator
ExposurePairGenerator
MeteringStateReducer
```

其中 `EvCalculator` 和 `ExposurePairGenerator` 应保持纯函数，便于后续编写单元测试。
