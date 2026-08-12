# LightMeter

面向胶片摄影的 Android 测光 App。

当前阶段已完成 MVP 版本，包含：

- Kotlin + Jetpack Compose 应用入口。
- CameraX `Preview` 与 `ImageAnalysis` 管线。
- 相机运行时权限处理。
- 平均测光与点击区域测光。
- 定格画面后锁定 EV 测算结果。
- 135、APS-C 与 6×4.5 画幅的取景范围模拟。
- 1/3 档光圈、快门与等效曝光组合。
- ISO、曝光补偿和主推荐曝光组合。
- 相机、测光、曝光和 UI 分层包结构。

## 环境要求

- Android Studio 或命令行 Android SDK
- JDK 17
- Android SDK Platform 35

macOS 本地环境初始化：

```bash
./scripts/setup-macos-android.sh
```

脚本会安装或检查：

- Homebrew 包：`openjdk@17`
- Homebrew 包：`android-commandlinetools`
- Android SDK：`platform-tools`
- Android SDK：`platforms;android-35`
- Android SDK：`build-tools;35.0.0`
- Shell 环境变量：`JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`

首次构建：

```bash
./gradlew assembleDebug
```

## 代码结构

```text
app/src/main/java/com/lightmeter/app/
├── camera/     CameraX 控制和曝光元数据接口
├── exposure/   曝光组合与画幅模型
├── metering/   测光配置、结果和图像分析入口
└── ui/         Compose 页面、权限和 ViewModel
```

## 设计文档

- [测光算法设计](docs/metering-algorithm-design.md)
- [曝光计算设计](docs/exposure-calculation-design.md)
- [Android 技术架构设计](docs/android-technical-architecture.md)
- [MVP 核心算法技术文档](docs/mvp-core-algorithms.md)

## License

[MIT](LICENSE)
