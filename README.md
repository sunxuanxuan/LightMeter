# FilmLightMeter

面向胶片摄影的跨平台测光 App。Android 版本已经可运行，iOS 版本处于设计和
迁移阶段。

## 仓库结构

```text
android/       Android 工程、构建脚本和平台工具
ios/           iOS 工程目录，当前包含实施入口说明
website/       官网、安装包分发和离线凭证签发服务
docs/android/  Android 专属架构、算法实现和用户文档
docs/ios/      iOS 产品设计、技术架构和迁移计划
docs/shared/   不依赖 Android/iOS API 的公共资料
```

详细边界见[仓库目录规范](docs/repository-structure.md)。

## Android

环境要求：

- Android Studio 或命令行 Android SDK
- JDK 17
- Android SDK Platform 35

macOS 初始化与构建：

```bash
./android/scripts/setup-macos-android.sh
cd android
./gradlew test assembleDebug
```

文档：

- [Android 使用说明书](docs/android/user-guide/FilmLightMeter-user-guide.md)
- [Android 快速指南（PDF）](docs/android/user-guide/FilmLightMeter-quick-guide.pdf)
- [Android 技术架构](docs/android/technical-architecture.md)
- [Android 核心算法实现](docs/android/mvp-core-algorithms.md)

## iOS

iOS 版本采用 SwiftUI、AVFoundation 和原生 Swift 实现。开始编码前以以下
文档作为实现和验收基线：

- [iOS 产品设计](docs/ios/product-design.md)
- [iOS 技术架构](docs/ios/technical-architecture.md)
- [iOS 迁移实施计划](docs/ios/migration-plan.md)
- [iOS 实施状态](docs/ios/implementation-status.md)
- [iOS 本地构建环境](docs/ios/local-build-environment.md)

## Website

官网采用 Next.js，默认使用本地 SQLite 和开发支付通道运行预览：

```bash
cd website
npm install
npm run dev
```

打开 `http://127.0.0.1:3000`。运行与生产配置见
[Website README](website/README.md)。

## 公共资料

- [双模式产品方案](docs/shared/dual-mode-product-design.md)
- [胶片预览模式技术方案](docs/shared/film-preview-technical-design.md)
- [胶片曝光宽容度参考](docs/shared/film-exposure-latitude-reference.md)
- [官网产品方案](docs/website/product-design.md)
- [官网技术方案](docs/website/technical-architecture.md)

## License

[MIT](LICENSE)
