# FilmLightMeter iOS 迁移实施计划

## 1. 原则

iOS 采用原生 Swift 重写，迁移的是业务语义、公式、阈值和验收标准，不逐行
翻译 Kotlin。每个阶段都必须有可运行产物和测试，先验证相机精度风险，再投入
完整界面开发。

## 2. 阶段划分

### 阶段 0：设计基线

交付：

- iOS 产品设计。
- iOS 技术架构。
- 仓库平台目录边界。
- Android 构建在迁移后仍然通过。

退出条件：

- 产品范围和最低系统版本确认。
- 相机元数据风险和激活方案完成评审。

### 阶段 1：相机技术验证

建立最小 Xcode 工程，只实现：

- 相机权限。
- 后置广角预览。
- `CVPixelBuffer` Y 平面读取。
- 帧时间戳、ISO、曝光时长和光圈观测。
- Full Range/Video Range 判断。
- 曝光偏置和变焦能力探测。
- 本地诊断页面。

真机记录灰卡在固定照度、自动曝光稳定和快速明暗切换下的数据。此阶段不复制
完整 Android UI。

退出条件：

- 可连续输出合法曝光快照。
- 明暗稳定后 EV 重复性标准差不超过 $0.1 EV$。
- 明确元数据与帧之间的可接受时延。
- `+/-2 EV` 偏置至少一个方向可稳定采集，失败路径可恢复。

### 阶段 2：领域算法

移植：

- 测光配置与归一化几何类型。
- 平均、中央重点和点测光。
- EV100 计算与时间平滑。
- 曝光组合、主推荐和安全快门。
- 胶片预设。
- FOV 取景框投影。
- 曝光图、风险阈值和积分图细节判断。

建立跨平台 JSON 测试向量，Android 与 iOS 同时验证。

退出条件：

- 纯算法测试全部通过。
- 曝光组合误差不超过 $1/6 EV$。
- 公共向量的跨平台 EV 差异不超过 $0.01 EV$。

### 阶段 3：主测光界面

实现：

- SwiftUI 主页面和相机预览桥接。
- ISO、EC、光圈、快门滚轮。
- 画幅与焦段。
- 三种测光模式和点击临时点测光。
- 权限、启动、中断和错误状态。
- 设置保存与恢复。

退出条件：

- 实时 EV 达到 8-12 Hz。
- 变焦期间不闪烁、不提交旧 revision。
- 前后台切换和相机中断可恢复。
- VoiceOver 和 Dynamic Type 基础验收通过。

### 阶段 4：冻结与风险图

实现：

- 从同一分析 buffer 生成冻结图。
- 同帧 `ExposureSnapshot`。
- 高光/暗部候选和渐进蒙层。
- 按需 `+/-2 EV` 探测。
- EC 调整后的离线重算。
- 取消、超时和曝光偏置恢复。

退出条件：

- 冻结图、曝光图和风险图空间对齐。
- 自然纯黑/纯白低纹理目标不会仅因亮度阈值被强制标记。
- 探测失败时保守回退，不阻塞恢复实时预览。
- 完整冻结通常在 1.2 秒内完成。

### 阶段 5：激活、校准与发布

实现：

- Keychain 设备标识。
- Ed25519 签名凭证验证。
- Debug 激活旁路和 Release 配置检查。
- 设备校准偏移。
- App Icon、启动资源、隐私清单和商店元数据。
- TestFlight 构建。

退出条件：

- 至少两类 iPhone 真机通过精度和稳定性验收。
- Release 不含私钥、HMAC 生成密钥或激活生成工具。
- Archive、签名、安装和 TestFlight 流程通过。

## 3. Android 到 iOS 映射

| Android | iOS |
| --- | --- |
| CameraX `Preview` | `AVCaptureVideoPreviewLayer` |
| CameraX `ImageAnalysis` | `AVCaptureVideoDataOutput` |
| `ImageProxy` Y 平面 | `CVPixelBuffer` plane 0 |
| Camera2 `CaptureResult` | sample 附件 + `AVCaptureDevice` 状态 |
| Compose | SwiftUI |
| `AndroidView` | `UIViewRepresentable` |
| `Bitmap` | `CGImage` / Core Image |
| ViewModel + StateFlow | `@MainActor @Observable` |
| Coroutines | Swift Concurrency |
| SharedPreferences | UserDefaults |
| Android Keystore/Prefs | Keychain |
| CameraX exposure index | `exposureTargetBias` |

## 4. 不直接复制的实现

- Android `Context`、LifecycleOwner 和权限辅助函数。
- CameraX `ViewPort`/`UseCaseGroup` 坐标代码。
- Camera2 时间戳元数据缓存。
- Android `Bitmap` 风险蒙层创建。
- Compose 控件尺寸和系统返回行为。
- 客户端内置 HMAC 激活密钥。

这些能力在 iOS 使用对应框架重新建模，但必须满足相同领域约束。

## 5. 首批任务

1. 创建 iOS 17、SwiftUI、Swift 6 工程和测试 Target。
2. 加入相机用途说明和仅竖屏配置。
3. 实现 `CameraService` 最小 session。
4. 建立曝光快照诊断界面和 `os.Logger` 输出。
5. 在两台真机采集灰卡技术验证数据。
6. 根据结果决定元数据同步与曝光锁定策略。
7. 技术验证通过后再移植 Domain 类型和算法。

## 6. 完成定义

iOS 迁移完成不是指界面能够启动，而是同时满足：

- 产品设计中的功能、精度、性能和隐私验收。
- 技术架构中的同帧、revision、并发和恢复约束。
- 公共算法测试向量跨平台一致。
- 真机测光校准和风险探测通过。
- TestFlight 可安装且 Release 激活链路可用。
