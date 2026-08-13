# FilmLightMeter iOS

原生 iOS 17 客户端，使用 SwiftUI、AVFoundation、Core Image 和 CryptoKit。

## 目录

```text
FilmLightMeter/
├── App/                 App 入口和生命周期
├── Activation/          Keychain 设备 ID 与 Ed25519 激活
├── Camera/              AVFoundation 预览、帧和曝光探测
├── Domain/              测光、曝光、取景框和风险算法
├── Features/            主测光和设置界面
├── Persistence/         UserDefaults 设置存储
└── Resources/           Asset Catalog
FilmLightMeterTests/     Xcode 单元测试
DomainValidation/        不依赖 Xcode 的领域验证程序
tools/                   工程生成和激活签发工具
```

## 环境

- macOS
- 完整版 Xcode 16.x
- iOS 17 SDK
- XcodeGen 2.46 或更新版本

如果 Mac App Store 的 Xcode 要求更高 macOS，请从 Apple Developer Downloads
下载兼容当前系统的 Xcode 16.x `.xip`，然后执行：

```bash
./scripts/setup-macos-ios.sh \
  --xcode-xip "$HOME/Downloads/Xcode_16.4.xip"
```

如果当前 macOS 已支持 App Store 最新版 Xcode，也可以一键安装、检查并完成
Simulator 构建：

```bash
./scripts/setup-macos-ios.sh
```

执行前需要在 Mac App Store 登录 Apple ID，并建议保留至少 40 GB 可用空间。
完整说明见[本地构建环境](../docs/ios/local-build-environment.md)。

## 生成与构建

`project.yml` 是 Xcode 工程的受控源。修改目录或 Target 后重新生成：

```bash
cd ios
xcodegen generate --spec project.yml
xcodebuild \
  -project FilmLightMeter.xcodeproj \
  -scheme FilmLightMeter \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

模拟器不能验证真实相机。运行测光和曝光探测必须选择 iPhone 真机。

## 领域验证

没有完整 Xcode 时仍可编译平台无关算法：

```bash
cd ios
swift run --disable-index-store DomainValidation
```

## Release 激活

私钥位于本机 `ios/.secrets/activation-private-key`，目录已被 Git 忽略。请将它
备份到安全的离线位置；丢失后无法继续签发与当前 App 公钥匹配的凭证。

为设备签发 30 天凭证：

```bash
cd ios
./tools/activation_signer.sh sign \
  .secrets/activation-private-key \
  A1B2C3D4E5F6A7B8 \
  30
```

重新生成密钥会使旧凭证失效。确需轮换时：

```bash
./tools/activation_signer.sh generate-key .secrets/activation-private-key.new
```

将命令输出的公钥更新到 `ActivationStore.swift`，并保留旧公钥的兼容策略后再
发布。

## 设计基线

- [产品设计](../docs/ios/product-design.md)
- [技术架构](../docs/ios/technical-architecture.md)
- [迁移计划](../docs/ios/migration-plan.md)
- [实施状态](../docs/ios/implementation-status.md)
- [本地构建环境](../docs/ios/local-build-environment.md)
