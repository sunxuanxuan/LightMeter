# 代码评审报告

- 仓库：LightMeter
- 检测模式：通用检测
- 检测范围：当前工作区支付链路改造（含未跟踪新源码）
- 生成时间：2026-08-18 20:57
- 检查文件：25
- 变更行数：1712

## 缺陷统计

- P0：0
- P1：5
- P2：0
- 合计：5

## 缺陷详情

### 1. [P1][安全漏洞] HTTP 明文链路会泄露并允许篡改付款确认数据

- 位置：`android/app/src/debug/java/com/lightmeter/app/paymentmonitor/PaymentMonitorConfig.kt:25-30`
- 置信度：9/10

**问题描述**

Debug 配置允许任意 HTTP 地址，而待确认响应包含解密后的完整设备 ID、邮箱、金额和通知证据。HMAC 只认证请求、不认证响应，因此同网络攻击者可以读取 PII，或篡改金额和到账标记诱导管理员确认。

**修复建议**

除回环地址的本地开发模式外强制 HTTPS，并禁止 HTTPS 降级重定向；如必须支持明文环境，至少对响应体签名且不要返回完整 PII。

---

### 2. [P1][业务语义问题] 单一结果 Cookie 会让较早的活动订单无法报付

- 位置：`website/app/api/orders/[orderNo]/payment-claimed/route.ts:17-23`
- 置信度：10/10

**问题描述**

所有订单共用 flm_result_session Cookie，新建第二笔订单会覆盖第一笔令牌；配置却允许同一买家存在两笔活动订单。第一笔订单随后无法打开结果页或调用“我已付款”，而找回接口又只允许 fulfilled 订单。

**修复建议**

改为按订单保存能力令牌，例如订单号到 token 的 HttpOnly Cookie 映射，或为每个订单使用独立 Cookie；报付时验证对应订单令牌。

---

### 3. [P1][业务语义问题] 宽限期内的真实付款无法进入人工确认

- 位置：`website/lib/dal.ts:555-560`
- 置信度：10/10

**问题描述**

claimPersonalPayment 在 expires_at 后立即拒绝，但金额会保留到 reservation_expires_at，通知事件也仍可按实际发生时间匹配。现在通知匹配不再签发或创建确认任务，因此截止前完成、截止后数秒点击报付的真实付款会永久搁置。

**修复建议**

在 reservation_expires_at 前允许报付，或仅当已匹配通知证明 observedAt 位于付款窗口内时创建确认任务。

---

### 4. [P1][健壮性问题] 确认任务过期清理不是原子操作

- 位置：`website/lib/dal.ts:221-246`
- 置信度：9/10

**问题描述**

getOrder 和 listPendingPaymentConfirmations 会在事务外执行多条清理 SQL。若进程在确认请求已改为 expired、订单尚未改为 expired 时退出，后续清理无法再选中该请求，订单会永久停留在 awaiting_confirmation 并从管理列表消失。

**修复建议**

将过期请求更新、订单状态迁移和金额预留删除放入同一事务；为已经处于外层事务的调用提供内部 helper 或 savepoint。

---

### 5. [P1][安全漏洞] 已签名的待确认查询可在时间窗口内重放

- 位置：`website/lib/payments/monitor-route-auth.ts:54-82`
- 置信度：9/10

**问题描述**

认证层只校验 nonce 格式，没有原子消费 nonce。同一条 pending-confirmations GET 可在 60 秒时间偏差窗口内重复通过并返回当时最新的完整邮箱和设备 ID 列表，不符合项目既定的 nonce 防重放约束。

**修复建议**

为所有管理端鉴权请求原子登记 monitorId、nonce、请求路径和过期时间；已存在的 nonce 立即返回 409/401，并定期清理过期记录。

---
