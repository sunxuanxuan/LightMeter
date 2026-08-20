# FilmLightMeter iOS 实施状态

## 已完成

- iOS 17 SwiftUI App 与单元测试 Target。
- XcodeGen 工程描述和已生成的 Xcode 工程。
- 相机权限、后置广角相机、预览和 Y 平面分析。
- Full Range / Video Range 亮度归一化。
- 曝光元数据快照、EV100、三种测光和时间平滑。
- ISO、EC、曝光组合和安全快门推荐。
- 预览两侧光圈、快门九档纵向刻度，滑动一侧时自动匹配等效曝光参数。
- 临时点测光位置标记、取景框外点击拦截和恢复默认测光。
- 135、APS-C、6x4.5、6x6 画幅与 FOV 变焦计算。
- 20–150 mm 目标焦段范围，并按设备实际变焦能力动态收缩。
- Canon New F-1、Canon AL-1 机型测光预设。
- 焦段变更期间暂停测光和冻结，实际倍率应用后再恢复新 revision 帧。
- 同一 `CVPixelBuffer` 生成分析快照和冻结图。
- 冻结图按 `2^EC` 线性增益异步渲染，支持在原图风险视图与 EC 模拟图间切换。
- 实时测光限制为 5 Hz，曝光图和冻结图仅在冻结请求时生成。
- 单帧高光/暗部宽容度检测和渐进蒙层。
- 风险边界按 0.1 EV 精度量化，仅在严格超出宽容度时触发。
- 已裁切高光不受宽容度边界影响，立即标记为高光风险。
- UserDefaults 设置持久化。
- 冻结状态保存设置后保留原冻结帧，并按新参数重算风险与模拟图。
- 相机权限拒绝页仍可返回模式首页。
- 双模式首页、专业模式返回入口和按页面管理相机会话生命周期。
- Keychain 设备 ID 和 Ed25519 签名激活凭证。
- Debug 激活旁路、Release 公钥验签和离线签发工具。
- App Icon、相机用途说明、竖屏配置和隐私清单。

## 已自动验证

- Swift 领域模块编译成功。
- `DomainValidation` 的 34 项核心断言通过。
- Swift Package 的 19 项领域测试通过。
- iOS Simulator SDK 下全部 Swift 源文件类型检查通过。
- Info.plist 格式校验通过。
- XcodeGen 工程生成成功且包含主要源码和资源。
- Ed25519 私钥权限为 `0600`，路径被 Git 忽略。
- 签发工具成功生成格式合法的设备凭证。

## 待验证

当前已安装 Xcode 26.6。命令行完整构建在 TRAE 沙箱中会因
`AssetCatalogSimulatorAgent` 无法加载沙箱注入库而停止；Swift 源码编译与领域
验证已通过。以下验证仍需在非沙箱终端或 Xcode 中执行：

1. iOS Simulator 编译和 XCTest。
2. iPhone 真机相机启动、方向和 Preview/冻结图空间对齐。
3. sample buffer 与 `AVCaptureDevice` 曝光状态的时序误差。
4. 灰卡校准后是否满足不超过 $1/6 EV$ 的误差目标。
5. 5 Hz 单帧管线的前后台、中断、热降频和连续运行测试。
6. 开发者签名、Archive 和 TestFlight。

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
Team。旧专业测光灰卡验收要求仅适用于历史实现；当前功能验收以
[产品需求文档](../product-requirements.md)为准。
