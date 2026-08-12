# 画幅与焦距自动变焦设计

## 1. 交互模型

设置页只选择胶片画幅：

```text
135
APS-C
6×4.5
6×6
```

预览页使用焦距滑块选择目标胶片镜头焦距：

```text
20mm ~ 120mm
步长 1mm
```

焦距改变后，系统自动调整 CameraX 变焦倍率，使目标取景范围的长边填满
预览。设备倍率不足时保留剩余取景框，并显示倍率上限状态。

## 2. 基础取景比例

画幅决定取景框比例，焦段变化不改变该比例：

```text
135 / APS-C：3:2
6×4.5：56:41.5
6×6：1:1
```

根据手机镜头和目标胶片系统的投影尺寸，计算未变焦时目标取景范围占
PreviewView 的比例：

$$
f_w = \frac{targetProjectionWidth}{displayedSensorWidth}
$$

$$
f_h = \frac{targetProjectionHeight}{displayedSensorHeight}
$$

其中：

$$
targetProjectionWidth
=
\frac{phoneFocalLength \times frameWidth}{targetFocalLength}
$$

$$
targetProjectionHeight
=
\frac{phoneFocalLength \times frameHeight}{targetFocalLength}
$$

## 3. 自动变焦

长边适配倍率：

$$
zoom_{fit} = \frac{1}{\max(f_w, f_h)}
$$

目标倍率交给 CameraX，并限制在设备支持范围：

$$
zoom_{actual}
=
clamp(zoom_{fit}, zoom_{min}, zoom_{max})
$$

取景框使用设备可达到的目标倍率计算：

$$
zoom_{effective}=clamp(zoom_{fit},zoom_{min},zoom_{max})
$$

设备无法达到理论倍率时，按 `zoom_effective` 保留剩余边框，确保显示范围、
测光 ROI 和真实预览一致。CameraX 回传倍率尚未到达 `zoom_effective` 时暂停
测光并禁用冻结，避免旧倍率帧被标记为新焦段结果：

$$
f'_w = clamp(f_w \times zoom_{effective}, 0, 1)
$$

$$
f'_h = clamp(f_h \times zoom_{effective}, 0, 1)
$$

设备能够达到目标倍率时：

$$
\max(f'_w, f'_h) = 1
$$

## 4. CameraX

`CameraController` 保存绑定后的 `Camera`，通过：

```kotlin
camera.cameraControl.setZoomRatio(targetZoom)
camera.cameraInfo.zoomState
```

设置目标倍率并监听实际倍率、最小倍率和最大倍率。

`Preview` 与 `ImageAnalysis` 继续共享同一个 `ViewPort` 和
`UseCaseGroup`，因此自动变焦后预览、测光 ROI 和曝光风险蒙层仍然使用
相同传感器裁剪区域。

## 5. 冻结行为

冻结状态下：

- 禁用焦距滑块。
- 保持当前 CameraX 倍率。
- 允许继续调整 ISO、曝光补偿和曝光组合。

恢复实时预览后，如果画幅或焦距已经变化，再应用新的目标倍率。

## 6. 安全快门

主推荐曝光组合根据滑块焦距动态选择安全快门：

```text
20mm ~ 35mm   -> 1/30s
36mm ~ 50mm   -> 1/60s
51mm ~ 90mm   -> 1/125s
91mm ~ 120mm  -> 1/250s
```
