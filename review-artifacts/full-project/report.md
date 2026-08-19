# 代码评审报告

- 仓库：LightMeter
- 检测模式：通用检测（全项目）
- 检测范围：当前 HEAD 全部受版本控制源码
- 生成时间：2026-08-17 16:45
- 检查文件：155
- 变更行数：95409

## 缺陷统计

- P0：0
- P1：5
- P2：0
- 合计：5

## 缺陷详情

### 1. [P1][业务语义问题] 延迟到账事件会在按付款时间匹配前使有效订单过期

- 位置：`website/lib/dal.ts:596-597`
- 置信度：10/10

**问题描述**

处理事务先用 receivedAt 调用 expireStalePersonalOrders，再用 observedAt 查询订单。付款发生在保留窗口内、但手机断网至窗口结束后才上传时，订单会先变成 expired，随后被 status = 'pending' 排除，真实付款永久进入 unmatched。

**修复建议**

先按 observedAt 匹配并核销事件，再过期其余订单；或在过期逻辑中保留 observation time 覆盖该订单窗口的候选。

---

### 2. [P1][业务语义问题] 生产环境的凭证找回始终不执行任何恢复或邮件交付

- 位置：`website/app/api/orders/recover/route.ts:16-18`
- 置信度：10/10

**问题描述**

生产请求在调用 recoverOrderSession 前固定返回 accepted。前端提示会向购买邮箱发送一次性链接，但仓库没有邮件发送实现，因此 Cookie 丢失后的已付款用户无法找回凭证。

**修复建议**

实现带限流和防枚举的一次性找回链接邮件流程，或在严格校验订单号和邮箱后安全恢复订单会话。

---

### 3. [P1][逻辑错误] 部署包内的正式 APK 永远会被判定为未发布

- 位置：`website/lib/db.ts:199-204`
- 置信度：10/10

**问题描述**

安装器配置 ANDROID_APK_PATH=./artifacts/FilmLightMeter-release.apk，但 release 检测要求路径包含 /outputs/apk/release/。生产启动后记录总是被写成 draft，下载页不可用且下载 API 返回 404。

**修复建议**

验证 APK 的实际签名和哈希，并接受安装器定义的 artifacts 路径；不要依赖 Gradle 构建目录字符串判断是否为正式包。

---

### 4. [P1][业务语义问题] 创建第二个订单会立即使浏览器失去第一个订单的访问权

- 位置：`website/app/api/orders/route.ts:94-104`
- 置信度：10/10

**问题描述**

所有订单共享一个 flm_result_session Cookie，而每个订单保存不同 token hash。第二次创建订单会覆盖 Cookie，导致首个订单结果页、轮询和付款码接口认证失败；配置又明确允许同一买家存在两个活动订单。

**修复建议**

使用按订单区分的 Cookie，或用签名浏览器会话安全保存多个 orderNo 到 token 的映射。

---

### 5. [P1][逻辑错误] 通知紧凑文本和展开文本重复金额时会漏掉真实付款

- 位置：`android/app/src/debug/java/com/lightmeter/app/paymentmonitor/AlipayNotificationParser.kt:43-46`
- 置信度：9/10

**问题描述**

支付宝通知可能同时在 text 和 bigText 中展示同一笔金额，但附带文本不同。当前只对完整字符串去重，再要求金额匹配数量恰好为 1；同一金额出现两次会直接返回 null，事件不会持久化或上报。

**修复建议**

先将匹配结果转换为分单位金额并去重；只有出现多个不同金额时才拒绝，同时补充 text 与 bigText 同额测试。

---
