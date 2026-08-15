# FilmLightMeter Website

FilmLightMeter 的使用说明、安装包分发和离线激活凭证签发网站。

## 本地运行

```bash
npm install
npm run dev
```

默认打开 `http://localhost:3000`。本地数据写入
`data/filmlightmeter.db`，该目录不会提交到 Git。

本地预览包含：

- 使用说明和最新版本页；
- Android 签名 Release APK 下载；
- SQLite 订单和签发记录；
- 模拟支付回调；
- 隔离的开发 Ed25519 密钥；
- 凭证找回和 `/admin` 数据查看。

开发凭证不能激活生产 App，也不会发生真实扣款。

## 配置

复制 `.env.example` 为 `.env.local` 后按需修改。Production 至少必须配置：

```text
DATA_ENCRYPTION_KEY_BASE64
LOOKUP_HMAC_PEPPER
ACTIVATION_PRIVATE_KEY_BASE64
ACTIVATION_SIGNING_KEY_ID
```

生产实现还需要将本地 SQLite 和模拟支付适配器替换为 PostgreSQL、真实支付渠道、
对象存储和邮件服务。服务端缺少生产密钥时会拒绝签发。

## 验证

```bash
npm run check
```

设计基线：

- [产品方案](../docs/website/product-design.md)
- [技术方案](../docs/website/technical-architecture.md)
- [离线激活协议](../docs/shared/offline-activation-protocol.md)
