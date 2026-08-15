# 离线激活凭证协议

## 1. 目标

FilmLightMeter 的 Android 和 iOS 客户端完全离线验证授权。官网在确认付款且取得
用户设备 ID 后签发凭证，客户端只内置 Ed25519 公钥，私钥不得进入客户端仓库、
APK、IPA 或前端代码。

## 2. 用户流程

1. App 首次启动并显示 16 位十六进制设备 ID。
2. 用户在官网下单，并填写或粘贴该设备 ID。
3. 支付服务确认付款成功。
4. 后端使用私钥为订单设备签发凭证。
5. 官网展示凭证，用户复制到 App。
6. App 离线验签，检查版本、设备 ID 和有效期后保存完整凭证。
7. App 每次启动重新验证完整凭证，不保存或信任独立的激活布尔值。

App 不请求激活接口，也不需要网络权限。没有设备 ID 的通用凭证不得签发，否则
同一凭证可以复制到任意设备。

## 3. v1 凭证格式

```text
Base64URL(payload-json).Base64URL(ed25519-signature)
```

签名输入是 UTF-8 编码的原始 `payload-json` 字节，不是重新序列化后的 JSON。
Base64URL 不包含换行和末尾 `=`。

Payload：

```json
{
  "deviceID": "A1B2C3D4E5F6A7B8",
  "expiresAt": 1798761600,
  "issuedAt": 1786723200,
  "version": 1
}
```

| 字段 | 类型 | 约束 |
|---|---|---|
| `version` | integer | 当前必须为 `1` |
| `deviceID` | string | 16 位大写十六进制，必须等于本机设备 ID |
| `issuedAt` | number | Unix 秒，有限且不小于 0 |
| `expiresAt` | number/null/缺省 | Unix 秒；存在时必须未过期 |

当前单个产品不在 Payload 中加入可由用户修改的产品类型。未来增加产品或授权等级
时必须提升协议版本，并同时保留旧版本验证策略。

## 4. 验证顺序

客户端必须按以下顺序处理：

1. 限制凭证、Payload、公钥和签名长度。
2. Base64URL 解码两个分段。
3. 使用内置公钥验证原始 Payload 的 Ed25519 签名。
4. 签名通过后解析 JSON。
5. 验证协议版本、设备 ID、签发时间和有效期。
6. 验证通过后保存完整凭证。

Android 使用 Bouncy Castle Ed25519，iOS 使用 CryptoKit
`Curve25519.Signing.PublicKey`。两端使用同一个发布公钥。

## 5. 签发

本地签发工具同时服务 Android 和 iOS：

```bash
cd ios
./tools/activation_signer.sh sign \
  .secrets/activation-private-key \
  A1B2C3D4E5F6A7B8
```

签发限时凭证时追加有效天数：

```bash
./tools/activation_signer.sh sign \
  .secrets/activation-private-key \
  A1B2C3D4E5F6A7B8 \
  365
```

官网后端应复用完全相同的 Payload 和签名规则。私钥应保存在 KMS 或仅后端可读的
密钥服务中；浏览器端、支付成功页 JavaScript 和客户端均不得接触私钥。

## 6. 运维边界

- 离线客户端无法实时获知退款、吊销或订单状态变化。
- 换机、恢复出厂设置或设备 ID 变化后需要重新签发。
- 私钥轮换时，新版本客户端应先同时信任新旧公钥，再切换后端签发密钥。
- 非对称签名阻止反编译者生成合法凭证，但不能阻止攻击者直接 Patch 客户端判断。
- Android Release 必须启用 R8；iOS Release 不得包含签发代码或私钥。
