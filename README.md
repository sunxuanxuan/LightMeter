# FilmLightMeter (Click & Click / 一拍即合)

面向一次性胶片相机和拍立得的跨平台拍前预览应用，覆盖微信/抖音/支付宝小程序、
Android App 和 iOS App 三个平台。

## 当前实现

### 小程序 (miniapp)

- Taro 4 + React + TypeScript + CSS Modules
- 微信、抖音、支付宝小程序及 H5 预览
- 一次性相机机型/胶片选择、拍摄、原图与模拟成片对比、保存
- 拍立得机型/相纸选择、相纸边框合成、保存
- 照片仅在本机 Canvas 中处理，不上传、不创建账号

### Android App

- Kotlin + Jetpack Compose + CameraX
- 专业测光、胶片预览、拍立得预览模式
- 高分辨率拍照与 GPU 曝光渲染
- Debug 版本可侧载安装；Release 版本经 R8 混淆

### iOS App

- SwiftUI + AVFoundation + Metal
- 专业测光、胶片预览模式
- 点测光、曝光组合推荐、画幅与变焦计算
- Ed25519 离线激活凭证

## 仓库结构

```text
miniapp/       Taro 小程序源码及跨端构建配置
android/       Android 工程、构建脚本和平台工具
ios/           iOS 工程、Swift 源码和平台工具
docs/          产品与算法资料
website/       官网与分发服务
```

## 各平台入口

| 平台 | 目录 | 文档 |
|------|------|------|
| 小程序 | `miniapp/` | 通过微信/抖音/支付宝开发者工具打开 |
| Android | `android/` | [技术架构](docs/android/technical-architecture.md) |
| iOS | `ios/` | [实施状态](docs/ios/implementation-status.md) |

## 资料

- [产品需求文档](docs/product-requirements.md)
- [仓库目录规范](docs/repository-structure.md)
- [胶片预览模式技术方案](docs/shared/film-preview-technical-design.md)
- [胶片曝光宽容度参考](docs/shared/film-exposure-latitude-reference.md)

## License

[MIT](LICENSE)
