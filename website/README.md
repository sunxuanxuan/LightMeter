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
- 支付宝扫码付款页面；
- 隔离的开发 Ed25519 密钥；
- 凭证找回和 `/admin` 数据查看。

未配置支付宝参数时，订单页面不会提供模拟付款入口。

## 配置

复制 `.env.example` 为 `.env.local` 后按需修改。Production 至少必须配置：

```text
DATA_ENCRYPTION_KEY_BASE64
LOOKUP_HMAC_PEPPER
ACTIVATION_PRIVATE_KEY_BASE64
ACTIVATION_SIGNING_KEY_ID
APP_BASE_URL
ALIPAY_APP_ID
ALIPAY_SELLER_ID
ALIPAY_PRIVATE_KEY
ALIPAY_PUBLIC_KEY
```

支付宝接入使用 `alipay.trade.precreate` 创建付款码，并通过
`/api/payments/alipay/notify` 接收异步通知。`APP_BASE_URL` 必须是支付宝能够访问的
HTTPS 地址；本地联调需要使用 HTTPS 隧道，并将它配置为该地址。

沙箱环境可以额外设置：

```text
ALIPAY_GATEWAY=https://openapi-sandbox.dl.alipaydev.com/gateway.do
```

生产部署还需要将本地 SQLite 替换为 PostgreSQL，并接入对象存储和邮件服务。
单实例本地预览可以继续使用 SQLite。服务端缺少支付参数或生产签名密钥时会拒绝付款
或签发。

## 验证

```bash
npm run check
```

设计基线：

- [产品方案](../docs/website/product-design.md)
- [技术方案](../docs/website/technical-architecture.md)
- [离线激活协议](../docs/shared/offline-activation-protocol.md)
