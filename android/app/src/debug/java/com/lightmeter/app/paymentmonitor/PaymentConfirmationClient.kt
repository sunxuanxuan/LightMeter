package com.lightmeter.app.paymentmonitor

import com.lightmeter.app.BuildConfig
import org.json.JSONObject
import java.time.Instant

internal data class PendingPaymentConfirmation(
    val orderNo: String,
    val deviceId: String,
    val email: String,
    val amountMinor: Int,
    val currency: String,
    val createdAt: Long,
    val claimedAt: Long,
    val confirmationExpiresAt: Long,
    val notificationObserved: Boolean,
)

internal sealed interface ConfirmationQueryResult {
    data class Success(
        val confirmations: List<PendingPaymentConfirmation>,
    ) : ConfirmationQueryResult

    data class Failure(val message: String) : ConfirmationQueryResult
}

internal sealed interface ConfirmationDecisionResult {
    data class Success(val status: String) : ConfirmationDecisionResult
    data class Failure(val message: String) : ConfirmationDecisionResult
}

internal object PaymentConfirmationClient {
    suspend fun fetch(config: PaymentMonitorConfig): ConfirmationQueryResult {
        config.validate()?.let {
            return ConfirmationQueryResult.Failure("请先保存有效的官网回传配置")
        }
        return try {
            val request = PaymentMonitorProtocol.createConfirmationQueryRequest(
                secret = config.secret,
            )
            val response = PaymentMonitorHttpClient.get(
                config = config,
                path = PaymentMonitorProtocol.CONFIRMATIONS_PATH,
                request = request,
            )
            if (response.statusCode !in 200..299) {
                return ConfirmationQueryResult.Failure(
                    "拉取待确认订单失败（HTTP ${response.statusCode}）",
                )
            }
            val array = JSONObject(response.body).getJSONArray("confirmations")
            val confirmations = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PendingPaymentConfirmation(
                            orderNo = item.getString("orderNo"),
                            deviceId = item.getString("deviceID"),
                            email = item.getString("email"),
                            amountMinor = item.getInt("amountMinor"),
                            currency = item.getString("currency"),
                            createdAt = Instant.parse(item.getString("createdAt"))
                                .toEpochMilli(),
                            claimedAt = Instant.parse(item.getString("claimedAt"))
                                .toEpochMilli(),
                            confirmationExpiresAt = Instant.parse(
                                item.getString("confirmationExpiresAt"),
                            ).toEpochMilli(),
                            notificationObserved = item.optBoolean(
                                "notificationObserved",
                                false,
                            ),
                        ),
                    )
                }
            }
            ConfirmationQueryResult.Success(confirmations)
        } catch (_: Exception) {
            ConfirmationQueryResult.Failure("拉取失败，请检查网络和官网配置")
        }
    }

    suspend fun decide(
        config: PaymentMonitorConfig,
        orderNo: String,
        decision: String,
    ): ConfirmationDecisionResult {
        config.validate()?.let {
            return ConfirmationDecisionResult.Failure(
                "请先保存有效的官网回传配置",
            )
        }
        return try {
            val (path, request) =
                PaymentMonitorProtocol.createConfirmationDecisionRequest(
                    orderNo = orderNo,
                    decision = decision,
                    monitorVersion = BuildConfig.VERSION_NAME,
                    secret = config.secret,
                )
            val response = PaymentMonitorHttpClient.post(
                config = config,
                path = path,
                request = request,
            )
            if (response.statusCode !in 200..299) {
                return ConfirmationDecisionResult.Failure(
                    "确认操作失败（HTTP ${response.statusCode}）",
                )
            }
            val json = JSONObject(response.body)
            check(json.optBoolean("accepted", false))
            ConfirmationDecisionResult.Success(json.getString("status"))
        } catch (_: Exception) {
            ConfirmationDecisionResult.Failure("确认操作失败，请检查网络后重试")
        }
    }
}
