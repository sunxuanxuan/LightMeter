# FilmLightMeter MiniApp

面向一次性胶片相机和拍立得的拍前预览小程序。正式产品不再以 Android 或
iOS App 形式提供。

## 当前实现

- Taro 4 + React + TypeScript + CSS Modules
- 微信、抖音、支付宝小程序及 H5 预览
- 一次性相机机型/胶片选择、拍摄、原图与模拟成片对比、保存
- 拍立得机型/相纸选择、相纸边框合成、保存
- 照片仅在本机 Canvas 中处理，不上传、不创建账号

小程序入口位于 `miniapp/src/`，全局页面配置见
`miniapp/src/app.config.ts`。
开发、预览、二维码和上传均通过当前 IDE 的小程序工具链完成。

## 仓库结构

```text
miniapp/       当前受支持的 Taro 小程序及跨端构建配置
docs/          产品与算法资料
android/       已停止支持的 Android 历史实现，仅供迁移对照
ios/           已停止支持的 iOS 历史实现，仅供迁移对照
website/       历史官网与分发服务
```

Android 与 iOS 目录不再作为发布目标，不接受针对原生 App 功能的新增开发。

## 资料

- [产品需求文档](docs/product-requirements.md)
- [胶片预览模式技术方案](docs/shared/film-preview-technical-design.md)
- [胶片曝光宽容度参考](docs/shared/film-exposure-latitude-reference.md)

## License

[MIT](LICENSE)
