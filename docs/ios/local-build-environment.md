# iOS 本地构建环境

## 1. 必需环境

FilmLightMeter iOS 只能在 macOS 上构建。完整环境包括：

| 环境 | 用途 |
| --- | --- |
| 完整版 Xcode 16.x | iOS SDK、Simulator、签名、Archive 和真机调试 |
| iOS 17+ SDK | 编译最低支持 iOS 17 的 App |
| Swift 6 | 编译应用与领域算法 |
| Xcode Command Line Tools | `xcodebuild`、`xcrun`、`simctl` |
| XcodeGen 2.46+ | 从 `ios/project.yml` 生成 Xcode 工程 |
| Homebrew | 安装 XcodeGen 和 Mac App Store CLI |
| Apple ID | 从 Mac App Store 安装 Xcode、进行个人真机签名 |

只安装 Command Line Tools 不够，它不包含 iOS SDK、Simulator 和完整签名工具。

建议安装前至少保留 40 GB 可用磁盘空间。Xcode、iOS SDK、Simulator Runtime
和 DerivedData 会占用较多空间。

## 2. 一键初始化

如果 Mac App Store 提示“需要更高版本 macOS”，不要安装最新版 Xcode。当前
macOS 15.x 应从 Apple Developer Downloads 下载兼容的 Xcode 16.x `.xip`
安装包，例如 Xcode 16.2、16.3 或 16.4。

下载地址：

```text
https://developer.apple.com/download/all/
```

下载后在仓库根目录执行：

```bash
chmod +x ios/scripts/setup-macos-ios.sh
./ios/scripts/setup-macos-ios.sh \
  --xcode-xip "$HOME/Downloads/Xcode_16.4.xip"
```

脚本会将 Xcode 解包到 `~/Applications`，并把 `xcode-select` 指向该版本。
如果希望解包到其他目录：

```bash
XCODE_INSTALL_DIR=/Applications \
  ./ios/scripts/setup-macos-ios.sh \
  --xcode-xip "$HOME/Downloads/Xcode_16.4.xip"
```

如果你的 macOS 已支持 App Store 最新版 Xcode，也可以直接执行：

```bash
chmod +x ios/scripts/setup-macos-ios.sh
./ios/scripts/setup-macos-ios.sh
```

脚本会：

1. 安装或检查 Homebrew。
2. 安装 XcodeGen。
3. 如果传入 `--xcode-xip`，从 `.xip` 解包 Xcode。
4. 如果未传入 `.xip` 且未发现完整 Xcode，尝试通过 Mac App Store 安装 Xcode。
5. 将 `xcode-select` 指向完整 Xcode。
6. 接受 Xcode 许可证并执行首次初始化。
7. 检查并补齐 iOS Device/Simulator SDK。
8. 根据 `ios/project.yml` 生成 Xcode 工程。
9. 执行一次无需签名的 Simulator Debug 构建。

Xcode 安装体积较大，下载时间取决于网络。过程中会请求管理员密码。

可用选项：

```bash
# 只检查，不安装、不修改系统配置
./ios/scripts/setup-macos-ios.sh --check-only

# Xcode 已通过其他方式安装，不允许脚本从 App Store 安装
./ios/scripts/setup-macos-ios.sh --skip-xcode-install

# 使用已下载的旧版兼容 Xcode
./ios/scripts/setup-macos-ios.sh \
  --xcode-xip "$HOME/Downloads/Xcode_16.4.xip"

# 完成环境配置，但跳过最终构建
./ios/scripts/setup-macos-ios.sh --skip-build

# 使用非标准位置的 Xcode
XCODE_APP=/Applications/Xcode-beta.app \
  ./ios/scripts/setup-macos-ios.sh
```

## 2.1 当前 macOS 15.x 推荐方案

如果系统是 macOS 15.x，且 App Store 显示最新版 Xcode 需要 macOS 26.2 或更高，
应使用 Apple Developer Downloads 的历史版本：

```bash
# 1. 浏览器下载 Xcode 16.x .xip
open 'https://developer.apple.com/download/all/?q=Xcode%2016'

# 2. 解包并配置
./ios/scripts/setup-macos-ios.sh \
  --xcode-xip "$HOME/Downloads/Xcode_16.4.xip"

# 3. 验证
xcodebuild -version
xcrun --sdk iphoneos --show-sdk-version
```

不要把 Xcode 26.x 强行安装到 macOS 15.x；即使绕过安装检查，iOS SDK、签名工具
和 `xcodebuild` 也可能无法正常工作。

## 3. 构建产物类型

### 3.1 Simulator App

初始化脚本默认生成：

```text
ios/DerivedData/Build/Products/Debug-iphonesimulator/FilmLightMeter.app
```

它只能安装到 iOS Simulator，不能安装到真实 iPhone。相机测光也不能用
Simulator 完成有效验证。

### 3.2 个人 iPhone 调试版

使用普通 Apple ID 即可安装到自己的 iPhone，但免费签名通常只有较短有效期。

步骤：

1. 打开 `ios/FilmLightMeter.xcodeproj`。
2. 在 `Xcode > Settings > Accounts` 登录 Apple ID。
3. 选择 FilmLightMeter Target 的 `Signing & Capabilities`。
4. 开启 `Automatically manage signing`。
5. 选择 Personal Team。
6. 将 Bundle Identifier 改成该账号下唯一的值。
7. 连接 iPhone，启用开发者模式并点击 Run。

这种方式适合开发调试，不适合向其他用户分发。

### 3.3 可分发 IPA

生成可长期安装或提交 TestFlight 的 IPA 需要：

- 有效的 Apple Developer Program 会员资格。
- Apple Development 或 Apple Distribution 证书。
- 唯一 Bundle ID。
- 对应的 Provisioning Profile。
- 在 Xcode 中配置正确的 Team。

创建 Archive：

```bash
cd ios

export DEVELOPMENT_TEAM=你的10位TeamID
export PRODUCT_BUNDLE_IDENTIFIER=com.yourcompany.filmlightmeter

xcodebuild \
  -project FilmLightMeter.xcodeproj \
  -scheme FilmLightMeter \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath build/FilmLightMeter.xcarchive \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
  PRODUCT_BUNDLE_IDENTIFIER="$PRODUCT_BUNDLE_IDENTIFIER" \
  archive
```

之后可在 Xcode Organizer 中选择：

- `Distribute App > TestFlight & App Store`
- `Distribute App > Ad Hoc`
- `Distribute App > Development`

Organizer 会根据选择生成或上传 IPA，并处理对应的签名配置。首次发布建议使用
Organizer，而不是手写 `ExportOptions.plist`，这样签名错误更容易定位。

## 4. 环境验证

```bash
xcodebuild -version
xcrun swift --version
xcrun --sdk iphoneos --show-sdk-version
xcrun --sdk iphonesimulator --show-sdk-path
xcodegen --version
```

重新生成并构建：

```bash
cd ios
xcodegen generate --spec project.yml
xcodebuild \
  -project FilmLightMeter.xcodeproj \
  -scheme FilmLightMeter \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData \
  CODE_SIGNING_ALLOWED=NO \
  build
```

## 5. 不能自动化的部分

出于 Apple 账号和私钥安全限制，脚本不会自动完成：

- 登录 Mac App Store 或 Xcode Apple ID。
- 加入 Apple Developer Program。
- 创建或导入签名证书。
- 创建 Bundle ID 和 Provisioning Profile。
- 选择开发者 Team。
- 开启 iPhone 开发者模式和信任此 Mac。
- App Store Connect/TestFlight 审核与发布。

这些步骤与个人 Apple 账号和开发者团队绑定，不能安全地写入仓库脚本。
