# FilmLightMeter iOS 技术架构

## 1. 技术选型

| 领域 | 方案 |
| --- | --- |
| 语言 | Swift 6 |
| UI | SwiftUI，必要处通过 `UIViewRepresentable` 接入 UIKit |
| 相机 | AVFoundation |
| 图像缓冲 | Core Video、Core Image |
| 数值优化 | Accelerate/vImage，风险蒙层后续可切换 Metal |
| 并发 | Swift Concurrency + AVFoundation 串行队列 |
| 状态 | Observation，`@Observable`、`@MainActor` |
| 持久化 | UserDefaults |
| 激活凭证 | Keychain + CryptoKit 公钥验签 |
| 测试 | Swift Testing/XCTest |

最低支持 iOS 17。相机功能必须在 iPhone 真机验证；模拟器只承担领域计算、
状态机和 UI 测试。

## 2. 工程结构

实施阶段在 `ios/` 下创建：

```text
ios/
├── FilmLightMeter.xcodeproj
├── FilmLightMeter/
│   ├── App/
│   ├── Features/
│   │   ├── Activation/
│   │   ├── Metering/
│   │   └── Settings/
│   ├── Camera/
│   ├── Domain/
│   │   ├── Exposure/
│   │   ├── Metering/
│   │   └── Risk/
│   ├── Persistence/
│   ├── Resources/
│   └── SupportingFiles/
├── FilmLightMeterTests/
│   ├── Domain/
│   ├── Features/
│   └── Fixtures/
└── README.md
```

禁止在 `ios/` 中复制 Android 框架适配层。公共内容以数学规范和测试向量共享，
不以源码文件共享。

## 3. 分层架构

```text
SwiftUI Views
    -> MeteringViewModel (@MainActor)
        -> CameraService
        -> MeteringEngine
        -> ExposureRecommendationEngine
        -> ExposureRiskEngine
        -> SettingsStore / ActivationStore
```

职责：

- `CameraService`：权限、session、预览、视频帧、曝光偏置和变焦。
- `MeteringEngine`：从像素与曝光快照计算亮度、EV 和低分辨率曝光图。
- `ExposureRecommendationEngine`：ISO/EC 换算、档位匹配和主推荐。
- `ExposureRiskEngine`：宽容度候选、探测帧比较和 ARGB/BGRA 蒙层。
- `MeteringViewModel`：状态机、revision、用户事件和服务编排。
- SwiftUI View：渲染状态，不直接操作 `AVCaptureDevice`。

Domain 类型不得 import SwiftUI、UIKit 或 AVFoundation。平台缓冲必须先由
Camera 层转换成 Domain 可以消费的只读视图或值类型。

## 4. 相机会话

### 4.1 会话配置

`CameraService` 持有：

```swift
AVCaptureSession
AVCaptureDeviceInput
AVCaptureVideoDataOutput
AVCaptureVideoPreviewLayer
```

session 配置和设备锁操作在专用串行 `sessionQueue` 执行。视频帧 delegate
使用独立串行 `videoOutputQueue`，并设置：

```swift
videoOutput.alwaysDiscardsLateVideoFrames = true
```

首选格式：

```text
kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
```

设备不支持时回退 Video Range。测光引擎必须根据像素格式分别归一化，不能
把 Video Range 的 `16...235` 当作 `0...255`。

### 4.2 摄像头选择

首版选择后置广角物理相机 `.builtInWideAngleCamera`，避免虚拟多摄设备在
临界倍率自动切镜头造成 EV、光圈和视场角突变。不支持广角物理设备时，再选择
可用后置设备并记录其类型。

切换 active format 后，缓存：

- `activeFormat.videoFieldOfView`
- 支持的 frame rate
- `minAvailableVideoZoomFactor`
- `maxAvailableVideoZoomFactor`
- `lensAperture`
- ISO 和曝光时长范围

这些能力属于当前 session，不能跨设备或重建后复用。

### 4.3 权限与生命周期

权限使用 `AVCaptureDevice.authorizationStatus(for: .video)`。App 进入后台时
停止 session；回到前台时重新检查权限和设备可用性，再启动 session。

监听：

- `AVCaptureSessionWasInterrupted`
- `AVCaptureSessionInterruptionEnded`
- `AVCaptureSessionRuntimeError`
- `AVCaptureDeviceWasDisconnected`

媒体服务重置后重建 session。所有异步回调携带 `sessionID`，旧 session 回调
不得修改当前状态。

## 5. 帧与元数据

### 5.1 帧输入

视频 delegate 从 `CMSampleBuffer` 取得：

- `CVPixelBuffer`
- presentation timestamp
- 图像方向
- 像素格式和色彩附件

使用 `CVPixelBufferLockBaseAddress(..., .readOnly)` 读取 Y 平面，处理完成后
成对解锁。实时路径不把整帧复制为 `UIImage`。

### 5.2 曝光元数据限制

iOS 公共 API 不保证为每个 `CMSampleBuffer` 提供 Android Camera2
`TotalCaptureResult` 等价物。实现按以下优先级取得曝光信息：

1. 读取 sample buffer 附件中可用且合法的 EXIF 曝光字段。
2. 在视频回调到达时读取同一 `AVCaptureDevice` 的 `exposureDuration`、
   `iso` 和 `lensAperture`，记录为近似同帧快照。
3. 字段缺失、非有限或设备正在大幅调整曝光时丢弃该帧。

不能把 `AVCaptureDevice` 当前值宣称为严格逐帧元数据。真机标定必须评估
AE 快速变化时的时间偏差；若误差不能满足要求，应在测光采样窗口短暂锁定曝光，
或改用可提供稳定元数据的捕获策略。

### 5.3 原子快照

每个被接受的分析帧创建不可变值：

```swift
struct ExposureSnapshot: Sendable {
    let exposureMap: ExposureMap
    let meteredEV100: Double
    let metadata: CameraExposureMetadata
    let timestamp: CMTime
    let revision: UInt64
    let sessionID: UUID
}
```

`exposureMap`、未平滑 EV、时间戳和 revision 必须由一次分析任务共同产生。
UI 平滑值只能用于实时显示，冻结风险基线必须使用快照中的未平滑值。

## 6. 测光算法移植

### 6.1 相机曝光 EV

$$
EV_{setting,100}
=
\log_2\left(\frac{N^2}{t}\right)
-
\log_2\left(\frac{ISO}{100}\right)
$$

其中 $N$ 为 `lensAperture`，$t$ 为曝光秒数。iOS 没有 Android
`CONTROL_POST_RAW_SENSITIVITY_BOOST` 的公开等价字段，因此数字增益和 ISP
差异通过设备校准偏移吸收，不编造额外增益。

### 6.2 Y 平面归一化

Full Range：

$$
Y_n = \frac{Y}{255}
$$

Video Range：

$$
Y_n = clamp\left(\frac{Y-16}{219},0,1\right)
$$

首版保持与 Android 相同的近似逆 gamma：

$$
Y_{linear}=Y_n^{2.2}
$$

场景 EV：

$$
EV_{100}
=
EV_{setting,100}
+
\log_2\left(\frac{Y_{measured}}{0.18}\right)
+
calibrationOffset
$$

测光模式、5% 双端裁剪均值、采样步长和时间平滑系数与 Android 测试向量
保持一致。实现时优先使用 256 桶直方图，不为每帧分配像素数组。

### 6.3 曝光图

风险图长边限制为 240 像素。单次遍历生成：

- `Float` EV100 数组。
- 原始 Y 数组。
- 高光裁切标志。
- 相机设置 EV 和快照标识。

数组存储可使用连续 `ContiguousArray` 或内部缓冲区，但跨并发边界后必须具有
明确所有权，禁止继续引用已解锁的 `CVPixelBuffer` 内存。

## 7. 预览、坐标与取景框

### 7.1 坐标空间

统一定义：

```text
DisplayNormalized: 竖屏预览左上角 (0,0)，右下角 (1,1)
BufferNormalized:  原始像素缓冲左上角到右下角
ViewfinderRect:    DisplayNormalized 中的有效取景框
```

点击、Overlay 和 Domain 配置使用 `DisplayNormalized`。Camera 层负责处理
orientation、`resizeAspectFill` 裁剪和缓冲宽高比，再转换到
`BufferNormalized`。转换函数必须有纯数学单元测试。

### 7.2 冻结帧一致性

实时显示可使用 `AVCaptureVideoPreviewLayer`。冻结时不得截取 preview layer，
因为它无法保证与分析快照同帧。应从生成 `ExposureSnapshot` 的同一个
`CVPixelBuffer` 创建冻结 `CGImage`，应用与预览相同的方向和裁剪矩阵。

风险蒙层和冻结图共享同一输出尺寸与变换，叠加层不再做二次经验裁剪。

### 7.3 目标焦段和变焦

iOS 不依赖未公开的传感器物理尺寸。根据目标胶片尺寸和焦距计算目标视场角：

$$
\theta_{target}
=
2\arctan\left(\frac{d_{film}}{2f_{target}}\right)
$$

根据 `activeFormat.videoFieldOfView` 得到手机基准视场角
$\theta_{phone}$，理论倍率为：

$$
zoom_{fit}
=
\frac{\tan(\theta_{phone}/2)}
{\tan(\theta_{target}/2)}
$$

横纵方向需结合 active format 与预览宽高比计算，最终取能保证目标长边适配的
倍率。实际倍率限制为设备可达范围：

$$
zoom_{actual}=clamp(zoom_{fit},zoom_{min},zoom_{max})
$$

取景框和 ROI 使用 `zoom_actual`，不使用未达到的 `zoom_fit`。设置
`videoZoomFactor` 后等待设备回报进入容差范围并接收新 revision 帧，再提交
新测光结果。

## 8. 曝光探测帧

### 8.1 流程

冻结基线后，先计算候选区域：

```text
暗部候选存在 -> 请求约 +2 EV exposureTargetBias
高光候选存在 -> 请求约 -2 EV exposureTargetBias
```

使用 `setExposureTargetBias(_:completionHandler:)`，目标值限制在设备
`minExposureTargetBias...maxExposureTargetBias`。实际变化不足 `1 EV` 时，
该方向视为不可用。

每次变更后：

1. 等待 completion。
2. 丢弃至少两个过渡帧。
3. 检查 `isAdjustingExposure` 和实际曝光设置变化。
4. 接受首个 revision、session 和尺寸匹配的稳定快照。
5. 全部探测结束或取消时只恢复一次原 bias。

探测状态由单一任务拥有，使用取消处理器保证恢复。新的冻结请求、恢复实时、
进入后台或 session 中断都必须取消该任务。

### 8.2 细节判断

沿用 Android 的积分图方案：

- 每张 Y 图构建亮度积分图和梯度积分图。
- 查询 `5 x 5` 邻域均值与平均梯度。
- 只有探测后亮度向预期方向变化且出现新局部结构时，标记细节风险。
- 探测不可用时回退为宽容度阈值和高光裁切的保守结果。

风险计算在后台执行。主线程只接收最终不可变蒙层和比例。

## 9. 并发模型

```text
MainActor
  MeteringViewModel / SwiftUI state

sessionQueue (serial)
  session configuration / device lock / zoom / bias

videoOutputQueue (serial)
  sample buffer intake / throttling / snapshot handoff

MeteringEngine actor or task executor
  histogram / EV / exposure map

ExposureRiskEngine actor
  integral images / probe comparison / mask
```

约束：

- `AVCaptureSession.startRunning()` 不在主线程执行。
- 每个 sample buffer 最多存在一个分析任务；落后时丢弃旧帧。
- UI 状态只在 MainActor 修改。
- 大数组不在 actor 之间重复复制；使用明确所有权的 `Sendable` 容器。
- 任意异步结果提交前检查 `sessionID` 和 `revision`。

## 10. 状态与持久化

`MeteringViewModel` 使用显式状态枚举，不用多个互相矛盾的布尔值表达相机阶段。
领域设置定义为 `Codable, Equatable, Sendable` 的 `AppSettings`。

`UserDefaultsSettingsStore`：

- 使用单一版本化 Codable payload。
- 解码失败回退默认值并保留可诊断错误。
- 保存时校验 ISO、EC、百分比和宽容度范围。
- 设置弹窗点击“保存”才持久化，临时 UI 编辑不直接写盘。

## 11. 激活设计

当前 Android HMAC 方案把生成密钥嵌入客户端，逆向后可生成任意激活码。iOS
发布版改用非对称签名：

```text
离线签发工具持有 Ed25519 私钥
App 只内置公钥
凭证 = version + deviceID + issuedAt + optionalExpiry + signature
```

设备 ID 首次启动时由 `SecRandomCopyBytes` 生成并保存到 Keychain。显示给用户
时使用分组后的短指纹。App 使用 CryptoKit `Curve25519.Signing.PublicKey`
验证凭证，并校验设备 ID。

私钥、未签名的万能凭证和发布证书不得进入仓库。Debug 构建可通过编译条件跳过
激活，但 Release 不允许包含凭证生成代码。

## 12. 错误处理和可观测性

用户可恢复错误：

- 相机权限未授予。
- 相机被其他任务占用。
- 探测帧超时。
- 暂时无法获得合法曝光元数据。

不可恢复错误：

- 无后置相机。
- 当前设备持续无法提供光圈、ISO 或曝光时长。
- session 多次重建失败。

日志使用 `os.Logger`，只记录状态、时间、revision、设备能力范围和数值错误。
不记录图像、激活凭证、完整设备 ID。Release 关闭逐帧日志。

## 13. 测试策略

### 13.1 单元测试

- EV、ISO/EC 和曝光组合公式。
- Full/Video Range Y 归一化。
- 三种测光 ROI 和 trimmed mean。
- 横竖方向、aspect fill 和点击坐标转换。
- FOV 与变焦倍率、设备上限回退。
- 风险阈值、渐进 Alpha 和积分图细节检测。
- ViewModel 状态迁移、revision 和取消。
- 设置迁移及激活凭证验签。

### 13.2 公共测试向量

后续在 `docs/shared/fixtures/` 保存 JSON 测试向量：

```text
metering-ev-v1.json
exposure-pairs-v1.json
viewfinder-projection-v1.json
exposure-risk-v1.json
```

Android 和 iOS 测试分别读取同一输入与期望输出。测试向量只包含合成数值，不含
用户相机帧。

### 13.3 真机测试

- 固定照度、灰卡和参考测光表校准。
- 暗部、高光、纯黑和纯白低纹理目标。
- 快速明暗切换下的元数据时序。
- 最小/最大变焦和焦段连续拖动。
- 前后台、锁屏、控制中心和相机中断恢复。
- 10 分钟持续运行的温度、帧率和内存。

## 14. 风险与决策门

最高风险是 iOS 逐帧曝光元数据不具备 Camera2 同等级保证。第一实施阶段必须先
完成真机技术验证：

1. 验证 sample buffer 附件实际可用字段。
2. 测量 `AVCaptureDevice` 状态与帧亮度变化的时延。
3. 验证自动曝光下 EV 重复性。
4. 验证 `exposureTargetBias` 探测帧是否稳定可控。

只有校准后误差达到 $1/6 EV$ 目标，才进入完整 UI 移植。若达不到，应先调整
捕获和曝光锁定策略，而不是用 UI 平滑掩盖系统误差。
