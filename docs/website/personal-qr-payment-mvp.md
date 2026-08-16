# 个人收款码支付 MVP 技术方案

## 1. 目标与边界

本方案用于 FilmLightMeter 短期验证付费转化，在尚未具备支付宝正式商户能力时，
通过个人支付宝收款码和 Android 通知监听完成低流量自动核销。

方案分为两部分：

1. 官网负责创建订单、分配唯一实付金额、接收监听事件、匹配订单和签发凭证；
2. Android Debug 版本负责监听本机支付宝到账通知并上传事件。

Android Release 版本继续作为用户下载版本，必须保持完全离线，不包含网络权限、
通知监听权限、监听服务、监控配置或通讯密钥。

本方案只把“可信 Debug 监控端确实观察到一条支付宝通知”作为支付证据，不等同于
支付宝官方签名回调。无法唯一匹配的事件必须进入人工复核，不得自动签发凭证。

## 2. 参考项目与改进

V免签的核心流程是：

1. 为待支付订单按 0.01 元步长分配未占用金额；
2. 在订单整个有效期内保留该金额；
3. Android `NotificationListenerService` 监听支付宝通知并提取金额；
4. 服务端按支付渠道、实付金额和待支付状态匹配订单；
5. 支付或超时后释放金额。

FilmLightMeter 保留该领域模型，但不复用以下实现：

- 不使用明文 HTTP；
- 不使用 MD5 拼接签名；
- 不使用 `Double` 表示金额；
- 不把通知时间戳当作支付交易号；
- 不在 APK 中硬编码监控端长期密钥；
- 不在匹配歧义时自动确认付款。

## 3. 总体架构

```text
用户浏览器
    |
    | 创建订单、轮询状态
    v
Next.js 官网 --------------------------+
    |                                 |
    | 保留金额、保存订单               | Ed25519 签发
    v                                 v
订单数据库                         激活凭证
    ^
    | HTTPS + HMAC-SHA256
    |
FilmLightMeter Debug
    |
    | NotificationListenerService
    v
支付宝到账通知
```

正式支付宝 SDK 与个人码 MVP 使用同一个支付 Provider 边界：

```text
official_alipay
personal_alipay_monitor
```

通过环境变量选择当前 Provider，不删除已经实现的支付宝官方接入，后续切换时不改变
订单页、状态轮询或凭证签发流程。

## 4. 官网适配逻辑

### 4.1 金额模型

- 商品标价：`990` 分；
- 实付金额：`890..990` 分；
- 差额：`0..100` 分；
- 数据库、接口和签名统一使用整数分；
- 页面明确展示“本单优惠价”和实际应付金额；
- 用户必须按页面金额付款，少付或多付不会自动核销。

金额不是只在 5 秒内唯一，而是在订单的完整占用窗口内唯一：

```text
订单有效期 15 分钟 + 到账宽限期 2 分钟
```

在同一支付宝收款渠道内，任意两个活跃订单不得使用相同实付金额。金额池耗尽时停止
创建新订单，并提示稍后重试。

### 4.2 数据模型

`orders` 增加或调整：

```text
list_amount_minor          标价，固定 990
amount_minor               实际应付金额
payment_provider           personal_alipay_monitor
expires_at                 用户支付截止时间
reservation_expires_at     金额释放时间，包含到账宽限期
payment_event_id           匹配到的监听事件，UNIQUE
status                     pending | payment_observed | issuing
                           | fulfilled | expired | manual_review
```

新增 `payment_amount_reservations`：

```text
id
provider
amount_minor
order_id                   UNIQUE
expires_at
created_at

UNIQUE(provider, amount_minor)
```

新增或扩展 `payment_events`：

```text
provider_event_id          UNIQUE
source                     android_notification_monitor
monitor_id
channel                    alipay
amount_minor
observed_at
notification_hash
source_authenticated       HMAC 是否验证成功
provider_verified          固定 false
process_status             received | matched | unmatched
                           | duplicate | manual_review
order_id                   可空
received_at / processed_at
```

`source_authenticated` 只能说明事件来自已注册的 Debug 监控端，不能记录成支付宝官方
验签成功。

### 4.3 金额分配

订单创建必须在数据库事务中执行：

1. 清理已超过 `reservation_expires_at` 的保留记录；
2. 读取 `890..990` 中尚未占用的金额；
3. 使用密码学安全随机数选择一个可用金额；
4. 插入金额保留记录；
5. 写入订单；
6. 提交事务。

SQLite 本地演示使用 `BEGIN IMMEDIATE` 保证串行分配。生产环境使用 PostgreSQL 唯一
约束处理并发冲突，冲突后重新选择金额，不能采用“先查询再无约束插入”。

### 4.4 个人收款码

- 收款码通过部署 Secret 或受限对象存储配置，不提交 Git；
- 结果页展示同一张个人支付宝收款码；
- 结果页突出显示实际金额和 15 分钟倒计时；
- 收款码接口仍要求订单结果会话 Cookie；
- 过期订单不再返回二维码；
- 页面继续使用现有轻量 JSON 轮询，不信任浏览器上报的付款结果。

### 4.5 监控端注册

新增管理员操作“注册监控设备”：

1. 服务端生成 5 分钟有效的一次性配对令牌；
2. Debug App 扫码或输入令牌；
3. App 通过 HTTPS 换取 `monitor_id` 和随机 256 位监控密钥；
4. 服务端只保存密钥哈希或使用服务端加密保存；
5. App 使用 Android Keystore 加密后存储密钥；
6. 管理员可以禁用设备并轮换密钥。

长期监控密钥不得写入 `BuildConfig`、资源文件或 Git。

### 4.6 监听事件接口

新增：

```text
POST /api/internal/payment-monitor/events
```

请求体：

```json
{
  "eventId": "随机且稳定的事件标识",
  "channel": "alipay",
  "amountMinor": 987,
  "observedAt": 1786812345000,
  "notificationHash": "SHA-256",
  "monitorVersion": "0.1.0-debug"
}
```

请求头：

```text
X-Monitor-Id
X-Timestamp
X-Nonce
X-Signature
```

签名：

```text
HMAC-SHA256(
  method + "\n" +
  path + "\n" +
  timestamp + "\n" +
  nonce + "\n" +
  SHA256(rawBody)
)
```

服务端验证：

- 强制 HTTPS；
- 请求体不超过 16 KiB；
- 时间偏差不超过 60 秒；
- `nonce` 和 `eventId` 均唯一；
- 监控设备处于启用状态；
- 使用常量时间比较 HMAC；
- 每个监控设备执行速率限制；
- 默认不上传完整通知文本、付款人昵称或其他个人信息。

### 4.7 事件匹配与签发

服务端先持久化事件，再在事务中匹配：

```text
provider = personal_alipay_monitor
amount_minor = event.amountMinor
status = pending
created_at <= observedAt
reservation_expires_at >= observedAt
```

- 恰好一个订单：进入 `payment_observed`；
- 没有订单：事件标记 `unmatched`，等待人工处理；
- 多个订单：事件和相关订单进入 `manual_review`；
- 重复事件：幂等返回成功，不重复签发。

自动匹配成功后，在同一数据库事务中：

1. 将订单置为 `issuing`；
2. 绑定唯一 `payment_event_id`；
3. 调用现有 Ed25519 signer；
4. 插入唯一 `order_id` 的 License；
5. 将订单置为 `fulfilled`；
6. 删除金额保留记录。

签发失败时保留事件与订单关联，订单进入 `manual_review`，不得把该金额立即分配给
其他订单。

### 4.8 过期、漏单与人工复核

- 定时任务每分钟关闭过期订单并释放金额；
- 请求订单页面时同时执行惰性过期，避免定时任务停机导致永久占用；
- 监听端离线时暂停创建个人码订单；
- 未匹配到账、通知解析失败和签发失败进入管理后台；
- 管理员只能在核对支付宝账单后确认，并填写支付宝账单号；
- 账单号全局唯一，人工确认同样复用统一签发事务。

## 5. Android App 适配逻辑

### 5.1 Debug 与 Release 共存

保留正式包：

```text
Release applicationId = com.lightmeter.app
```

Debug 增加：

```text
applicationIdSuffix = ".debug"
versionNameSuffix = "-debug"
Debug applicationId = com.lightmeter.app.debug
Debug app name = FilmLightMeter Debug
```

两者具有独立应用数据、SharedPreferences、权限和生命周期，可以在同一 Android 用户
空间同时安装。当前设备 ID 包含 `context.packageName`，因此 Debug 与 Release 会得到
不同设备 ID；这是预期隔离。Debug 当前跳过激活，Release 继续执行完整离线验签。

不使用 `sharedUserId`，不共享激活凭证和应用数据。

### 5.2 Source Set 隔离

目录规划：

```text
android/app/src/main/
    正式用户功能、Camera 权限、离线激活

android/app/src/debug/
    AndroidManifest.xml
    paymentmonitor/
        PaymentMonitorActivity.kt
        AlipayNotificationListener.kt
        NotificationParser.kt
        MonitorApiClient.kt
        MonitorCredentialStore.kt
        MonitorEventStore.kt
        MonitorRetryWorker.kt

android/app/src/debug/res/
    Debug 名称、监听工具 UI 和图标
```

Debug Manifest 独占：

```text
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
NotificationListenerService
PaymentMonitorActivity
```

监听工具作为 Debug 包中的第二个 Launcher Activity 提供独立入口。这样无需修改
`src/main/MainActivity.kt`，Release 的用户界面和代码路径保持不变。

Release Manifest 必须继续只有相机相关权限，不允许通过 `src/main` 引入
`INTERNET`、通知访问或监控 Service。

### 5.3 通知监听

`NotificationListenerService` 只处理：

```text
packageName = com.eg.android.AlipayGphone
```

读取并规范化：

```text
EXTRA_TITLE
EXTRA_TEXT
EXTRA_BIG_TEXT
EXTRA_SUB_TEXT
```

只有命中已知支付宝收款关键词时才解析金额。金额解析使用严格正则和整数分转换，
禁止使用 `Double`。若通知中出现零个或多个候选金额，则只记录本地解析失败，不上传
付款事件。

通知规则独立成纯 Kotlin `NotificationParser`，使用真实脱敏样本建立单元测试。规则
变化时发布新的 Debug APK，不在服务端远程下发可执行正则。

### 5.4 本地事件队列

监听到事件后先写入 Debug App 本地 Outbox，再尝试上传：

```text
event_id
amount_minor
observed_at
notification_hash
state                    pending | sending | acknowledged | failed
attempts
next_attempt_at
```

- `eventId` 基于随机 UUID，不使用通知时间戳；
- 对 Android 通知 key、postTime、金额和内容哈希做本地去重；
- 使用 WorkManager 指数退避重试；
- 服务端确认持久化后才标记 `acknowledged`；
- 网络中断或进程重启不得丢失事件；
- 本地只保留有限时间和数量，避免存储无限增长。

### 5.5 配置与安全存储

Debug 监听页提供：

- 扫描配对二维码；
- 当前监控设备 ID；
- 通知访问权限状态；
- 最近心跳时间；
- 最近一次成功上传时间；
- 待上传事件数量；
- 暂停/恢复监听；
- 清除配对信息。

监控密钥使用 Android Keystore 保护，日志不得输出密钥、签名、完整通知内容或完整
请求体。Debug APK 可以被反编译，因此安全边界依赖“密钥安装后生成/下发并可撤销”，
不能依赖隐藏在 APK 中的常量。

### 5.6 心跳

Debug App 在以下时机上传心跳：

- NotificationListener 连接成功；
- App 打开监听状态页；
- 每次成功上传付款事件；
- 系统允许时执行周期性后台任务。

官网根据 `last_seen_at` 判断监控端状态。超过配置阈值时停止创建新的个人码订单，
已有订单仍保留到超时并允许人工核销。不能为了高频心跳持有永久 WakeLock。

## 6. Release 不变性保证

Release APK 必须满足：

1. `applicationId` 仍为 `com.lightmeter.app`；
2. 仍使用现有 Release 签名；
3. 下载页仍只发布 `outputs/apk/release/app-release.apk`；
4. 不包含 `INTERNET`、`POST_NOTIFICATIONS` 或通知监听 Service；
5. 不包含监控 API 地址、监控密钥、通知解析规则和调试入口；
6. 激活逻辑、设备 ID 算法和 Ed25519 公钥保持不变；
7. `BuildConfig.DEBUG == false` 时继续要求离线激活。

CI 增加 Release 审计：

```text
apkanalyzer manifest permissions app-release.apk
apkanalyzer manifest services app-release.apk
apkanalyzer dex packages app-release.apk
```

若 Release 出现网络权限或 `paymentmonitor` 包，构建直接失败。

## 7. 测试计划

### 7.1 官网

- 101 个金额的边界和耗尽测试；
- 并发创建订单不重复分配金额；
- 订单过期和宽限期释放测试；
- HMAC、时间窗、nonce 和 eventId 重放测试；
- 无匹配、多匹配和重复通知测试；
- 重复事件不重复签发凭证；
- 签发失败进入人工复核；
- 正式支付宝 Provider 的现有测试继续通过。

### 7.2 Android Debug

- 支付宝个人码、商家码和店员通知脱敏样本解析测试；
- 整数、单小数、双小数金额测试；
- 多金额和无金额通知拒绝测试；
- 非支付宝包名拒绝测试；
- 断网重试、进程重启恢复和事件去重测试；
- 密钥清除、设备禁用和重新配对测试。

### 7.3 构建与安装

```bash
cd android
./gradlew :app:assembleDebug :app:assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm list packages | grep com.lightmeter.app
```

预期同时存在：

```text
com.lightmeter.app
com.lightmeter.app.debug
```

最后检查 Release Manifest 和 DEX，确认监听逻辑只存在于 Debug APK。

## 8. 实施顺序

1. 先完成 Android 构建变体隔离并验证双版本共存；
2. 实现官网金额保留表、订单状态和个人码页面；
3. 实现监控端注册、HMAC 事件接口和幂等存储；
4. 实现 Debug 通知解析、本地 Outbox 和上传；
5. 接通统一付款履约与 Ed25519 签发事务；
6. 增加监控健康检查、人工复核和过期清理；
7. 完成真实支付宝通知的端到端小额测试；
8. 保留正式支付宝 Provider，准备后续无缝切换。

## 9. MVP 验收条件

- 同一手机可同时安装 Debug 和 Release；
- Release 与当前用户下载版本的包名、签名、权限和激活行为一致；
- Release APK 中不存在监听和联网代码；
- Debug 能稳定识别真实支付宝到账通知；
- 同一有效期内实付金额不重复；
- 断网恢复后通知不会丢失或重复签发；
- 无法唯一匹配时不会自动发放凭证；
- 支付成功后页面自动显示设备绑定的 Ed25519 激活凭证；
- 管理员可以处理漏单、禁用监控设备和轮换监控密钥。
