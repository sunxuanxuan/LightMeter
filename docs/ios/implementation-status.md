# FilmLightMeter iOS 实施状态

## 已完成

- iOS 17 SwiftUI App 与单元测试 Target。
- XcodeGen 工程描述和已生成的 Xcode 工程。
- 相机权限、后置广角相机、预览和 Y 平面分析。
- Full Range / Video Range 亮度归一化。
- 曝光元数据快照、EV100、三种测光和时间平滑。
- ISO、EC、曝光组合和安全快门推荐。
- 135、APS-C、6x4.5、6x6 画幅与 FOV 变焦计算。
- 焦段变更 revision 拒绝旧帧。
- 同一 `CVPixelBuffer` 生成分析快照和冻结图。
- 高光/暗部宽容度、渐进蒙层和积分图细节探测。
- 按候选区域进行约 `+/-2 EV` 曝光偏置探测。
- 800 ms 探测超时、取消和原偏置恢复。
- UserDefaults 设置持久化。
- Keychain 设备 ID 和 Ed25519 签名激活凭证。
- Debug 激活旁路、Release 公钥验签和离线签发工具。
- App Icon、相机用途说明、竖屏配置和隐私清单。

## 已自动验证

- Swift 领域模块编译成功。
- `DomainValidation` 的 7 项核心断言通过。
- Swift 源文件语法解析通过。
- Info.plist 格式校验通过。
- XcodeGen 工程生成成功且包含主要源码和资源。
- Ed25519 私钥权限为 `0600`，路径被 Git 忽略。
- 签发工具成功生成格式合法的设备凭证。

## 环境阻塞

当前 macOS 只安装并选中了 Command Line Tools，没有完整 Xcode 和 iOS SDK：

```text
xcode-select: tool 'xcodebuild' requires Xcode
```

因此以下验证仍必须在安装 Xcode 后执行：

1. iOS Simulator 编译和 XCTest。
2. iPhone 真机相机启动、方向和 Preview/冻结图空间对齐。
3. sample buffer 与 `AVCaptureDevice` 曝光状态的时序误差。
4. `exposureTargetBias` 在不同 iPhone 上的实际稳定时间和可达范围。
5. 灰卡校准后是否满足不超过 $1/6 EV$ 的误差目标。
6. 前后台、中断、热降频和连续运行测试。
7. 开发者签名、Archive 和 TestFlight。

## 安装 Xcode 后的验证命令

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
cd ios
xcodegen generate --spec project.yml
xcodebuild \
  -project FilmLightMeter.xcodeproj \
  -scheme FilmLightMeter \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

真机运行前还需要在 Xcode 的 Signing & Capabilities 中选择 Apple Developer
Team。测光精度必须按[产品设计](product-design.md)中的灰卡验收流程执行。
