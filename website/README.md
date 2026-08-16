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
- 个人支付宝收款码、订单唯一金额和监听回调；
- 隔离的开发 Ed25519 密钥；
- 凭证找回和 `/admin` 数据查看。

## 配置

复制 `.env.example` 为 `.env.local` 后按需修改。Production 必须提供：

```text
ACTIVATION_PRIVATE_KEY_BASE64
ACTIVATION_SIGNING_KEY_ID
```

部署脚本会在首次安装时自动生成 `DATA_ENCRYPTION_KEY_BASE64`、
`LOOKUP_HMAC_PEPPER`、`PAYMENT_MONITOR_ID` 和 `PAYMENT_MONITOR_SECRET`。升级时会
复用 `/etc/filmlightmeter-website.env` 中的现有值；也可以在输入环境文件中显式
设置这些变量来覆盖。

默认支付 Provider 为：

```text
PAYMENT_PROVIDER=personal_alipay_monitor
```

网站从 `PERSONAL_ALIPAY_QR_IMAGE_PATH` 读取个人支付宝收款码。每笔订单从
`当前标价 - ¥1.00` 到当前标价之间分配一个在订单有效期内唯一的实付金额；
标价低于 ¥1.01 时，实付下限固定为 ¥0.01。到账事件通过：

```text
POST /api/internal/payment-monitor/events
```

接收 Android Debug 监听端的 HMAC-SHA256 签名事件。生产环境的
`PAYMENT_MONITOR_ID` 和 `PAYMENT_MONITOR_SECRET` 安装后可从
`/etc/filmlightmeter-website.env` 读取，并需原样配置到监听设备。Secret 至少 32 个
字符，只能保存在服务器和监听设备的安全存储中，不得提交 Git。

Debug 监听端可通过同一组 Monitor 凭据调用：

```text
POST /api/internal/payment-monitor/pricing
```

更新官网标价。新价格只影响更新后创建的订单，已有订单继续使用创建时保存的金额
快照。

切换到支付宝官方当面付时设置 `PAYMENT_PROVIDER=alipay`，并配置 `APP_BASE_URL`、
`ALIPAY_APP_ID`、`ALIPAY_SELLER_ID`、应用私钥和支付宝公钥。官方异步通知地址为
`/api/payments/alipay/notify`。

生产部署还需要将本地 SQLite 替换为 PostgreSQL，并接入对象存储和邮件服务。
单实例本地预览可以继续使用 SQLite。服务端缺少支付参数或生产签名密钥时会拒绝付款
或签发。

## 验证

```bash
npm run check
```

## 云服务器安装

部署包支持带 systemd 的 Linux 服务器，推荐 Ubuntu 22.04/24.04，并要求 Node.js
`>= 22.13`。解压后先准备生产环境文件：

```bash
cp .env.example /root/filmlightmeter.env
chmod 600 /root/filmlightmeter.env
```

其中 `ACTIVATION_PRIVATE_KEY_BASE64` 必须是 `ios/.secrets/activation-private-key`
原始 32 字节内容的 Base64，不能重新生成。检查脚本会验证它与 App 内置公钥一致。
其余可自动生成的变量保持为空即可。

执行检查和安装：

```bash
./scripts/deploy/check_environment.sh deploy
sudo ./scripts/deploy/install.sh \
  --env-file /root/filmlightmeter.env \
  --port 3000
```

安装结果：

- 程序：`/opt/filmlightmeter-website/current`
- SQLite 和持久化数据：`/var/lib/filmlightmeter-website`
- 环境变量：`/etc/filmlightmeter-website.env`
- systemd 服务：`filmlightmeter-website`
- 本地监听：`127.0.0.1:3000`

公网访问必须配置 HTTPS 反向代理。Nginx 示例位于
`scripts/deploy/nginx.conf.example`，其中对公开下单接口额外设置了 IP 限流。

常用运维命令：

```bash
systemctl status filmlightmeter-website
journalctl -u filmlightmeter-website -f
systemctl restart filmlightmeter-website
```

设计基线：

- [产品方案](../docs/website/product-design.md)
- [技术方案](../docs/website/technical-architecture.md)
- [个人收款码 MVP](../docs/website/personal-qr-payment-mvp.md)
- [离线激活协议](../docs/shared/offline-activation-protocol.md)
