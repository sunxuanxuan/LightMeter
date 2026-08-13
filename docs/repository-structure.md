# 仓库目录规范

## 1. 目标

FilmLightMeter 同时维护 Android 和 iOS 客户端。目录必须让文件的平台归属
可以从路径直接判断，避免将 CameraX、AVFoundation、Compose 或 SwiftUI
代码放入含义模糊的公共目录。

## 2. 标准结构

```text
FilmLightMeter/
├── android/
│   ├── app/                 Android 应用源码和资源
│   ├── gradle/              Gradle Wrapper 与版本目录
│   ├── scripts/             Android 环境脚本
│   └── tools/               Android 激活和发布工具
├── ios/
│   ├── FilmLightMeter/      iOS App Target，实施阶段创建
│   ├── FilmLightMeterTests/ iOS 单元测试，实施阶段创建
│   └── README.md
├── docs/
│   ├── android/             Android 专属文档和截图
│   ├── ios/                 iOS 专属文档和截图
│   └── shared/              平台无关的领域资料
├── README.md
└── LICENSE
```

## 3. 归属规则

放入 `android/` 或 `docs/android/` 的内容：

- Kotlin/JVM、Jetpack Compose、CameraX、Camera2 代码。
- Gradle 配置、AndroidManifest、Android 资源。
- Android 权限、生命周期、设备标识和签名说明。
- Android 截图、APK 使用指南及平台校准结果。

放入 `ios/` 或 `docs/ios/` 的内容：

- Swift、SwiftUI、UIKit、AVFoundation、Metal 代码。
- Xcode 工程、Asset Catalog、Entitlements 和 Info.plist 配置。
- iOS 权限、生命周期、Keychain、签名和 TestFlight 说明。
- iOS 截图、IPA/TestFlight 使用指南及平台校准结果。

只有满足以下条件的内容才能放入 `docs/shared/`：

- 不引用 Android 或 iOS 框架类型。
- 数学定义和业务含义在两个平台完全一致。
- 修改时应同时约束两个客户端，例如胶片宽容度资料和校准基准。

当前不建立共享运行时代码模块。Android Kotlin 与 iOS Swift 分别实现领域
算法，通过公共测试向量校验一致性。只有在重复维护成本被实际数据证明后，
才评估 Kotlin Multiplatform 或 C/C++ 核心库。

## 4. 命名规则

- 平台目录使用固定名称 `android` 和 `ios`，不使用 `mobile`、`app` 等
  无法表达归属的顶层名称。
- 文档文件名使用小写 kebab-case。
- 平台内部可沿用平台惯例：Kotlin 包名使用小写，Swift 类型文件使用
  UpperCamelCase。
- 图片和测试数据必须位于所属平台的 `assets/` 或公共 `fixtures/` 下。
- 构建产物、IDE 用户状态、签名证书和密钥不得提交。

## 5. 构建入口

Android 命令必须从 `android/` 执行：

```bash
cd android
./gradlew test assembleDebug
```

iOS 工程创建后，命令必须显式指定 `ios/` 下的 workspace 或 project：

```bash
xcodebuild \
  -project ios/FilmLightMeter.xcodeproj \
  -scheme FilmLightMeter \
  -destination 'platform=iOS Simulator,name=iPhone 16' \
  test
```

真实相机、曝光元数据和探测帧只能在 iPhone 真机验证，模拟器测试不能替代
真机验收。
