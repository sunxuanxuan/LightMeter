# FilmLightMeter 官网技术方案

## 1. 文档信息

- 系统：FilmLightMeter 配套官网
- 版本：V1.0
- 日期：2026-08-15
- 状态：实施前技术基线
- 关联文档：
  - [官网产品方案](./product-design.md)
  - [离线激活凭证协议](../shared/offline-activation-protocol.md)

## 2. 技术目标

系统需要以尽量小的运维成本实现：

1. 静态使用说明和响应式页面；
2. Android 正式 APK 下载与 iOS 商店/TestFlight 跳转；
3. 无账号订单、支付确认和凭证找回；
4. 支付成功后为指定设备签发 Ed25519 凭证；
5. 发布、订单、补发和换机的最小运营能力；
6. 私钥、支付密钥、安装包和用户数据的安全管理。

App 不调用官网 API。官网只负责交易、签发和分发，客户端继续完全离线验签。

## 3. 架构原则

- **单体优先**：V1 使用一个模块化 Web 应用和一个 PostgreSQL，不拆微服务；
- **服务端可信**：付款状态、签发和下载版本只由服务端决定；
- **异步副作用**：邮件和可重试签发通过任务/outbox 执行；
- **不可变发布**：APK 对象不可覆盖，新版本使用新对象键；
- **无账号购买**：公开订单通过高熵 HttpOnly 结果会话访问，不通过连续订单号授权；
- **最少数据**：不采集 App 行为，不在日志记录完整设备 ID 或凭证；
- **协议复用**：官网签发结果必须通过 Android/iOS 客户端测试向量。

## 4. 推荐技术栈

| 层级 | 推荐 | 说明 |
|---|---|---|
| Web | Next.js App Router + TypeScript | 页面、服务端接口和管理端保持同一工程 |
| UI | CSS Modules/Tailwind + design tokens + Lucide | 适合紧凑工具型界面 |
| 校验 | Zod | 请求、环境变量和表单共享约束 |
| 数据库 | PostgreSQL | 订单事务、唯一约束和审计 |
| ORM | Prisma | 类型化模型、迁移和事务支持 |
| 对象存储 | S3 兼容存储 + CDN | APK 和说明图片 |
| 支付 | Provider Adapter | V1 只接一个渠道，国内选微信/支付宝，海外选 Stripe |
| 邮件 | 事务邮件服务 | 订单确认、凭证交付和找回链接 |
| 限流 | 托管 Redis/KV，可选 | 订单、找回和管理登录限流 |
| 测试 | Vitest + Playwright | 领域、接口和关键购买流程 |
| 部署 | Node.js 托管平台或容器 | 必须支持原始 webhook body 和后台任务 |

支付渠道、部署区域和对象存储供应商保持适配器边界，避免业务代码绑定单一平台。
若主要用户在中国大陆，应优先选择境内可稳定访问的托管与 CDN，并按实际情况完成
域名备案和支付商户接入；不要默认依赖境外 Serverless 可用性。

## 5. 总体架构

```text
                         +----------------------+
                         | Android / iOS 用户浏览器 |
                         +-----------+----------+
                                     |
                                     v
+------------------+       +---------+----------+       +------------------+
| CDN / Object     |<------| Next.js Web App    |------>| PostgreSQL       |
| APK / Guide Media|       | Public + Admin API |       | Orders/Licenses  |
+------------------+       +----+-----------+---+       +------------------+
                              |           |
                       webhook|           |sign
                              v           v
                       +------+---+   +---+----------------+
                       | Payment  |   | Isolated Signer /  |
                       | Provider |   | Secret Manager/KMS |
                       +----------+   +--------------------+
                              |
                              | async delivery
                              v
                       +------+------+
                       | Email       |
                       | Provider    |
                       +-------------+
```

V1 的 signer 可以作为仅服务端可调用的模块运行在 Web/Worker 进程中。私钥由 Secret
Manager 在运行时注入，不写入镜像和仓库。若所选云 KMS 原生支持 Ed25519，优先让
KMS 完成签名；否则将签发模块隔离到最小权限的 Worker。

## 6. 工程结构

建议在当前仓库增加独立 `website/`：

```text
website/
├── app/
│   ├── (public)/
│   │   ├── guide/
│   │   ├── download/
│   │   └── activate/
│   ├── admin/
│   └── api/
├── components/
├── lib/
│   ├── activation/
│   ├── auth/
│   ├── db/
│   ├── download/
│   ├── email/
│   ├── payment/
│   └── validation/
├── prisma/
├── public/
├── tests/
└── scripts/
```

边界要求：

- `lib/activation` 只接收已确认付款的内部命令；
- `lib/payment` 的 provider 实现只负责创建支付和验证回调；
- 页面组件不得导入私钥、数据库客户端或 signer；
- 安装包不放入 Git，也不放入 Next.js `public/`，统一进入对象存储。

## 7. 核心领域模型

V1 不再需要“随机激活码池”。支付成功后直接生成设备绑定签名凭证。

### 7.1 Release

```text
id
platform                 android | ios
channel                  production | testflight
version_name
version_code
minimum_os
storage_key              Android 使用
store_url                iOS 使用
sha256                   Android 使用
file_size                Android 使用
release_notes
status                   draft | published | withdrawn
published_at
created_at / updated_at
```

同一 `platform + channel` 只允许一个当前 published 版本。切换版本时使用事务。

### 7.2 Order

```text
id                       UUID，内部主键
order_no                 可展示、不可作为鉴权
result_session_hash      结果页高熵会话 token 的哈希
buyer_email_ciphertext
buyer_email_hash
platform
device_id_ciphertext
device_id_hash
device_id_suffix
amount_minor
currency
payment_provider
payment_trade_no
status                   pending | paid | issuing | fulfilled
                         | cancelled | refunded | manual_review
terms_version
paid_at / refunded_at
created_at / updated_at
```

设备 ID 进入数据库前必须规范化为 16 位大写十六进制。用于查询的邮箱和设备 ID
保存带服务端 pepper 的 HMAC；需要交付和售后的原值使用应用层加密保存。

### 7.3 License

```text
id                       UUID
order_id                 UNIQUE
device_id_hash
credential_ciphertext
protocol_version         1
signing_key_id
issued_at
expires_at               默认 NULL，永久授权
status                   issued | superseded | refunded
supersedes_license_id
created_at
```

`order_id UNIQUE` 是重复支付回调不重复签发的最后一道约束。换机重新签发创建新
License，并将旧记录标记为 superseded；旧设备上的离线凭证仍无法远程失效。

### 7.4 PaymentEvent

```text
id
provider
provider_event_id        UNIQUE
payload_hash
signature_verified
process_status
order_id
error_code
received_at / processed_at
```

完整支付回调原文只按支付渠道合规要求短期保留，日志仅记录事件 ID、订单 ID 和
处理结果。

### 7.5 OutboxJob

```text
id
topic                    issue_license | send_credential | send_recovery
deduplication_key        UNIQUE
payload_ciphertext
status                   pending | running | succeeded | failed
attempts
next_attempt_at
last_error_code
created_at / updated_at
```

### 7.6 AdminAuditLog

记录管理员、操作类型、目标订单/版本、变更摘要、来源 IP、时间和结果。审计记录
只追加，不提供普通后台删除能力。

## 8. 状态机

### 8.1 订单状态

```text
pending
  -> paid
  -> issuing
  -> fulfilled

pending -> cancelled
paid/issuing -> manual_review
paid/issuing/fulfilled -> refunded
```

只允许服务端支付回调把订单从 `pending` 推进到 `paid`。浏览器支付返回页只能
查询状态，不能修改状态。

### 8.2 发布状态

```text
draft -> published -> withdrawn
```

发布新 Android 版本前必须验证对象存在、文件大小和 SHA-256。撤回当前版本时应
自动回退到最近一个可用版本，或者关闭下载按钮并触发告警。

## 9. 凭证签发

### 9.1 协议

保持客户端已经实现的 v1：

```text
Base64URL(payload-json).Base64URL(ed25519-signature)
```

```json
{
  "deviceID": "A1B2C3D4E5F6A7B8",
  "issuedAt": 1786723200,
  "version": 1
}
```

永久授权省略 `expiresAt`。需要限时授权时使用 Unix 秒整数。

### 9.2 序列化

签名针对原始 UTF-8 Payload 字节。服务端必须使用稳定序列化：

1. 只允许协议定义字段；
2. 字段按 `deviceID`、`expiresAt`、`issuedAt`、`version` 字典序输出；
3. 不输出多余空格和换行；
4. Base64URL 去除末尾 `=`；
5. Ed25519 签名长度固定 64 字节。

客户端实际验证原始 Payload，因此空格和字段顺序不会影响验签；稳定序列化用于
生成可重复测试向量和减少不同实现差异。

### 9.3 签发前置条件

Signer 必须同时确认：

- 内部调用身份有效；
- 订单状态为 `paid` 或获批的 `manual_review`；
- 金额、币种和产品配置匹配；
- 设备 ID 已规范化并通过格式校验；
- 当前订单不存在已签发 License，或操作明确为换机重新签发；
- 使用的 `signing_key_id` 处于 active 状态。

签发接口不能暴露给浏览器，也不能接受客户端传入的 `paid=true`。

### 9.4 与现有工具兼容

开发和灾备签发使用：

```bash
cd ios
./tools/activation_signer.sh sign \
  .secrets/activation-private-key \
  A1B2C3D4E5F6A7B8
```

网站 signer 上线前必须用同一设备 ID 生成测试凭证，并分别通过 Android
`ActivationCredentialVerifierTest` 和 iOS `ActivationCredentialVerifierTests`
验证。

## 10. 支付与签发流程

```text
Browser        Web API          PostgreSQL       Payment        Worker/Signer
   |              |                 |               |                |
   | create order |                 |               |                |
   |------------->| normalize ID    |               |                |
   |              | create pending |               |                |
   |              |--------------->|               |                |
   |              | create checkout|-------------->|                |
   |<-------------| checkout URL    |               |                |
   |----------------------------------------------->| pay            |
   |              |<-------------------------------| signed webhook |
   |              | verify raw body |               |                |
   |              | transaction: event + paid + outbox              |
   |              |---------------->|               |                |
   |              |                 |-------------->| issue job      |
   |              |                 |               | sign payload   |
   |              |                 |<--------------| save license   |
   | poll result  |                 |               |                |
   |------------->| read by session |               |                |
   |<-------------| credential/status               |                |
```

### 幂等要求

- `provider + provider_event_id` 唯一；
- `payment_provider + payment_trade_no` 唯一；
- `licenses.order_id` 唯一；
- `outbox.deduplication_key` 唯一；
- Worker 重试时先查询现有 License，存在则返回原凭证；
- 不通过“先查询再插入”代替数据库唯一约束。

## 11. API 设计

所有公开接口使用 HTTPS 和 JSON。错误返回稳定 `error_code`，不返回堆栈或内部
支付信息。

### 11.1 最新版本

```http
GET /api/releases/latest?platform=android
GET /api/releases/latest?platform=ios
```

Android 返回版本、系统要求、文件大小、SHA-256 和下载地址；iOS 返回 App Store
或 TestFlight URL。

### 11.2 下载

```http
GET /api/downloads/android/latest
```

服务端读取当前 published Release 后返回 `302` 到不可变 CDN 对象。响应禁止缓存
“latest”重定向太久；带版本号的对象允许长期 immutable 缓存。

### 11.3 创建订单

```http
POST /api/orders
Idempotency-Key: <uuid>

{
  "platform": "android",
  "deviceID": "A1B2C3D4E5F6A7B8",
  "email": "user@example.com",
  "termsVersion": "2026-08-15"
}
```

返回 `orderNo`、金额和支付跳转信息，同时设置路径受限的
`HttpOnly; Secure; SameSite=Lax` 结果会话 Cookie。原始会话 token 不暴露给
JavaScript，数据库仅存哈希。

### 11.4 支付回调

```http
POST /api/payments/{provider}/webhook
```

必须读取原始请求体并使用 provider SDK 验证签名。回调处理不依赖 Cookie、浏览器
会话或前端返回参数。

### 11.5 查询订单结果

```http
GET /api/orders/{orderNo}
```

只有结果会话 Cookie 哈希匹配时才返回完整凭证。支付平台返回 URL 只携带订单号，
不能携带凭证或会话 token。响应设置：

```text
Cache-Control: no-store
Referrer-Policy: no-referrer
```

### 11.6 凭证找回

```http
POST /api/orders/recovery

{
  "orderNo": "FLM-...",
  "email": "user@example.com"
}
```

无论订单是否存在都返回相同提示。匹配时异步发送短期、单次使用的恢复链接。
恢复链接将 token 放在 URL fragment 中；恢复页通过 POST 交换为 HttpOnly 结果
会话后立即清除 fragment，避免 token 进入服务端访问日志和 Referer。

```http
POST /api/orders/recovery/exchange

{
  "token": "<recovery-token>"
}
```

交换成功后 token 立即失效，服务端设置新的结果会话 Cookie。

### 11.7 管理端

管理接口放在 `/api/admin/*`，至少覆盖：

- 创建和发布 Release；
- 查询订单与回调状态；
- 重发原凭证；
- 审核换机重新签发；
- 标记退款；
- 查询审计记录。

## 12. 安装包发布

### 12.1 Android

推荐 CI 流程：

```text
tag/release workflow
  -> 使用受保护签名密钥构建 Release APK
  -> 执行单测、lint 和安装验证
  -> 计算 SHA-256 和文件大小
  -> 上传版本化对象键
  -> 后端校验对象元数据
  -> 创建 draft Release
  -> 管理员确认并切换为 published
```

对象键示例：

```text
releases/android/0.1.0/FilmLightMeter-0.1.0-android.apk
```

不得覆盖同名对象。APK 签名密钥与激活凭证私钥必须是两套独立密钥。

### 12.2 iOS

CI 或 Xcode Archive 上传 App Store Connect。官网只保存 App Store/TestFlight
公开链接、版本和状态，不托管 IPA。测试渠道与正式渠道分开配置。

## 13. 安全设计

### 13.1 私钥与密钥

- Ed25519 私钥只存在于 Secret Manager/KMS 或隔离 signer；
- 支付 webhook secret、邮件密钥、数据库密钥均使用独立 secret；
- CI 的 Android APK 签名密钥与凭证签名私钥完全分离；
- 生产 secret 不进入 `.env.example`、日志、错误追踪或构建产物；
- signer 运行身份只拥有读取指定密钥和写入 License 的权限。

### 13.2 支付

- 只相信通过官方 SDK 验签的 webhook；
- 校验商户号、金额、币种、订单号和交易状态；
- 先记录事件唯一 ID，再推进订单状态；
- 管理员手工标记付款必须二次确认并写审计日志；
- 退款后禁止找回和重新签发，但明确无法远程停用已离线激活的 App。

### 13.3 Web

- 管理员使用 OIDC/Passkey 或强密码加 MFA；
- 管理端使用独立授权中间件，不能只依赖隐藏路由；
- 所有变更请求启用 CSRF 防护或严格 SameSite Cookie；
- 设置 CSP、HSTS、`X-Content-Type-Options` 和安全 Referrer Policy；
- 订单创建、状态查询和找回接口限流；
- 结果与恢复 token 使用至少 256 bit CSPRNG，数据库只存哈希；
- 结果会话使用 HttpOnly Cookie，支付返回 URL 和普通查询参数不得携带 token；
- 用户输入渲染依赖框架默认转义，不使用任意 HTML；
- 不接受用户控制的下载 URL、对象键或重定向目标。

### 13.4 数据与日志

禁止记录：

- 完整设备 ID；
- 完整签名凭证；
- result/recovery token；
- 支付卡号、支付账号或 webhook 原始 secret；
- 数据库和 KMS 凭证。

允许记录订单内部 ID、设备 ID 后四位、provider event ID、状态、耗时和稳定错误码。

### 13.5 下载安全

- Release 记录只能引用受控 bucket 前缀；
- 上传后重新计算服务端 SHA-256，不信任客户端上传值；
- CDN 强制 HTTPS 和正确 APK MIME；
- 官网展示与数据库一致的 SHA-256；
- 发布前验证 APK 包名、版本号和签名证书指纹；
- 下载异常时停止 latest 指向，而不是继续分发未知文件。

## 14. 缓存与性能

- 使用说明静态生成，发布内容时增量更新；
- 最新版本元数据短缓存 1-5 分钟；
- 版本化 APK 和图片使用长期 immutable CDN 缓存；
- 订单、凭证和管理页面全部 `no-store`；
- 支付结果页前台轮询采用 2、3、5 秒退避，最长约 2 分钟；
- 数据库为订单号、支付交易号、邮箱哈希和状态建立索引。

官网业务量较小时不需要独立消息中间件。PostgreSQL outbox + 定时 Worker 足以覆盖
签发和邮件重试；达到明显积压后再迁移到托管队列。

## 15. 可观测性

### 指标

- `http_request_total`、延迟和 5xx；
- 下载重定向成功率；
- 支付 webhook 验签失败率和处理延迟；
- paid 到 fulfilled 的耗时；
- signer 成功率和失败码；
- outbox 积压、重试和死信；
- 邮件发送成功率；
- 当前 published Release 缺失告警。

### 告警

- 连续支付成功但未签发；
- signer/KMS 不可用；
- webhook 验签失败突增；
- 最新 APK 对象不存在或哈希不一致；
- 管理员高风险操作异常增加；
- 数据库备份失败。

错误追踪必须在发送前清洗设备 ID、凭证、token 和支付字段。

## 16. 测试策略

### 16.1 单元测试

- 设备 ID 规范化；
- 订单状态机；
- 金额和币种校验；
- 稳定 JSON 序列化；
- Ed25519 签发和篡改拒绝；
- 结果会话和恢复 token 哈希验证；
- provider webhook 适配器。

### 16.2 集成测试

- 重复 webhook 只产生一个 License；
- 签发失败后重试返回同一凭证；
- 未付款订单不能调用 signer；
- 退款订单不能找回或补发；
- 发布切换和回退；
- 对象哈希不匹配时拒绝发布。

### 16.3 跨平台契约测试

仓库保存不含生产私钥的测试密钥和固定测试向量：

```text
device ID
payload bytes
public key
credential
expected result
```

同一向量必须同时通过：

- Website signer/verifier；
- Android `ActivationCredentialVerifierTest`；
- iOS `ActivationCredentialVerifierTests`。

生产私钥和生产凭证不得成为测试 fixture。

### 16.4 E2E

Playwright 覆盖：

- 手机/桌面使用说明导航；
- Android/iOS 下载状态；
- 创建订单、模拟支付、等待签发、复制凭证；
- 关闭页面后通过结果链接恢复；
- 找回接口防枚举；
- 管理员发布版本和换机重签。

## 17. CI/CD

### Website

```text
pull request
  -> typecheck
  -> lint
  -> unit/integration tests
  -> Playwright
  -> dependency/security scan
  -> preview deployment

main
  -> repeat checks
  -> database migration review
  -> production deployment
  -> smoke test
```

数据库迁移采用向后兼容的 expand/contract 策略。支付 webhook 和 signer 修改必须
先在沙箱环境使用固定测试向量验证。

### Release artifact

Android/iOS 客户端发布流水线与 Website 分离。网站只能消费已经通过客户端发布
流水线生成的产物元数据，不能在 Web 请求中即时构建安装包。

## 18. 环境与配置

最少需要：

```text
DATABASE_URL
APP_BASE_URL
DATA_ENCRYPTION_KEY
LOOKUP_HMAC_PEPPER
ACTIVATION_SIGNING_KEY_ID
PAYMENT_PROVIDER
PAYMENT_WEBHOOK_SECRET
PAYMENT_API_CREDENTIALS
OBJECT_STORAGE_BUCKET
OBJECT_STORAGE_ENDPOINT
OBJECT_STORAGE_CREDENTIALS
EMAIL_PROVIDER_CREDENTIALS
ADMIN_AUTH_CONFIG
```

生产私钥不建议直接作为普通环境变量长期配置。应通过密钥服务按运行身份获取，
或者由隔离 signer 持有。

环境分为：

- local：支付和邮件模拟器、测试私钥；
- staging：支付沙箱、独立数据库和 bucket、测试私钥；
- production：真实支付、生产数据库、生产签名密钥。

三套环境不得共享支付 webhook、数据库或签名私钥。

## 19. 备份与恢复

- PostgreSQL 每日备份并启用时间点恢复；
- 对象存储启用版本保护或删除保护；
- 生产 Ed25519 私钥至少有一份受控离线灾备；
- 定期演练从订单和 License 恢复凭证交付；
- 私钥丢失后不能重新签发与旧客户端公钥匹配的凭证，因此密钥备份优先级高于
  普通应用配置；
- 私钥泄露时先发布同时信任新旧公钥的客户端，再切换后端签发密钥。

## 20. 实施阶段

### 阶段 1：只读官网

- Next.js 工程、设计系统和三页骨架；
- 使用说明；
- Android APK 元数据与下载；
- iOS App Store/TestFlight 状态；
- Release 管理。

### 阶段 2：交易与签发

- Order 数据模型；
- 单一支付渠道；
- webhook 验签和幂等；
- Ed25519 signer；
- 结果页和跨平台契约测试。

### 阶段 3：交付与运维

- 邮件发送和找回；
- 换机人工审核；
- outbox 重试；
- 审计、监控、告警和备份；
- 安全测试与正式上线检查。

## 21. 上线前待确认

以下业务配置不影响架构，但必须在实施前确定：

1. 面向中国大陆还是海外用户；
2. 首发支付渠道和商户主体；
3. 永久授权价格、币种与退款规则；
4. 官网域名、客服邮箱和隐私主体；
5. Android Release 签名与自动发布方式；
6. iOS 使用 TestFlight 还是已经具备 App Store 上架条件；
7. 部署区域、对象存储和邮件供应商；
8. 换机审核规则和每个订单允许重新签发的次数。
