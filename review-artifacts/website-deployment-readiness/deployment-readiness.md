# 官网部署就绪检查

## 结论

修正后未发现 P0-P2 级别的剩余部署阻断项。官网可完成生产构建，页面图片、支付宝付款码和 Android Release APK 均已进入部署包；生产模式下设备码下单、会话 Cookie、付款结果页和付款码读取链路验证通过。

## 已修复问题

1. 使用说明页首图原先依赖运行时远程生图 URL，部署包不包含该图片。
   - 图片已保存为 `website/public/guide/film-camera-light-meter.jpeg`。
   - 页面改为读取本地 `/guide/film-camera-light-meter.jpeg`。

2. `create_package.sh` 原先没有复制 `public/` 内容。
   - 已将 `public` 加入部署包复制清单。

3. 部署包中的 APK 路径不满足运行时代码的 Release 路径校验，线上下载页会显示未发布。
   - APK 统一放置为 `artifacts/outputs/apk/release/FilmLightMeter-release.apk`。
   - 打包、环境检查、配置校验和安装脚本已使用同一路径。

## 验证结果

- `npm run check`：通过。
- ESLint：通过。
- Vitest：4 个测试文件、25 个测试全部通过。
- Next.js 生产构建：通过。
- 独立部署包内 `npm ci`、生产配置校验、生产构建：通过。
- `npm prune --omit=dev` 后生产服务启动与健康检查：通过。
- 生产依赖审计：0 个漏洞。
- 部署包及包内 `SHA256SUMS`：全部通过。
- Android APK：v2 签名验证通过，单一 RSA 4096 位签名者。
- 错误 Origin 下单：HTTP 403，`INVALID_ORIGIN`。
- 正确 HTTPS Origin 下单：成功创建订单并返回结果页 URL。
- 结果会话 Cookie：包含 `Secure`、`HttpOnly`、`SameSite=lax`。
- 付款结果页：成功显示支付宝付款提示。
- 页面首图、付款码和下载 APK：HTTP 200，下载文件 SHA-256 与部署包源文件一致。

## 最终部署包

- `dist/FilmLightMeter-website-deploy-20260818T042001Z.tar.gz`
- `dist/FilmLightMeter-website-deploy-20260818T042001Z.tar.gz.sha256`

## 环境边界

当前检查环境为 macOS，无法原样执行 Linux systemd 安装步骤。安装脚本已完成静态审查、Shell 语法检查，并在解包目录逐项模拟了 `npm ci`、配置校验、生产构建、依赖裁剪、生产启动和健康检查。正式服务器仍需使用真实 HTTPS 域名配置 `APP_BASE_URL`，并按 Nginx 示例传递 `Host`、`X-Forwarded-Host` 和 `X-Forwarded-Proto`。
