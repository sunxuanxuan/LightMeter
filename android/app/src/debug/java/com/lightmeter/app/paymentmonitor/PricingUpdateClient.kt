package com.lightmeter.app.paymentmonitor

import com.lightmeter.app.BuildConfig
import org.json.JSONObject

internal sealed interface PricingUpdateResult {
    data class Success(
        val priceMinor: Int,
        val discountMaxMinor: Int,
        val minimumPaymentMinor: Int,
    ) : PricingUpdateResult

    data class Failure(val message: String) : PricingUpdateResult
}

internal object PricingAmountParser {
    private val amountPattern = Regex("""^([0-9]{1,7})(?:\.([0-9]{1,2}))?$""")

    fun parseMinorUnits(value: String): Int? {
        val match = amountPattern.matchEntire(value.trim()) ?: return null
        val yuan = match.groupValues[1].toLongOrNull() ?: return null
        val cents = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }
            .toLongOrNull() ?: return null
        val amountMinor = yuan * 100 + cents
        return amountMinor.takeIf { it in 1..100_000_000 }?.toInt()
    }
}

internal object PricingUpdateClient {
    suspend fun fetch(config: PaymentMonitorConfig): PricingUpdateResult {
        config.validate()?.let {
            return PricingUpdateResult.Failure("请先保存有效的官网回传配置")
        }
        return try {
            val request = PaymentMonitorProtocol.createPricingQueryRequest(
                secret = config.secret,
            )
            val response = PaymentMonitorHttpClient.get(
                config = config,
                path = PaymentMonitorProtocol.PRICING_PATH,
                request = request,
            )
            if (response.statusCode !in 200..299) {
                return PricingUpdateResult.Failure(
                    "获取官网定价失败（HTTP ${response.statusCode}）",
                )
            }
            parsePricing(JSONObject(response.body).getJSONObject("pricing"))
        } catch (_: Exception) {
            PricingUpdateResult.Failure("获取官网定价失败，请检查网络和官网配置")
        }
    }

    suspend fun update(
        config: PaymentMonitorConfig,
        priceMinor: Int,
    ): PricingUpdateResult {
        config.validate()?.let {
            return PricingUpdateResult.Failure("请先保存有效的官网回传配置")
        }
        return try {
            val request = PaymentMonitorProtocol.createPricingUpdateRequest(
                priceMinor = priceMinor,
                monitorVersion = BuildConfig.VERSION_NAME,
                secret = config.secret,
            )
            val response = PaymentMonitorHttpClient.post(
                config = config,
                path = PaymentMonitorProtocol.PRICING_PATH,
                request = request,
            )
            if (response.statusCode !in 200..299) {
                return PricingUpdateResult.Failure(
                    "官网拒绝更新（HTTP ${response.statusCode}）",
                )
            }
            val responseJson = JSONObject(response.body)
            check(responseJson.optBoolean("updated", false))
            parsePricing(responseJson.getJSONObject("pricing"))
        } catch (_: Exception) {
            PricingUpdateResult.Failure("价格同步失败，请检查网络和官网配置")
        }
    }

    private fun parsePricing(pricing: JSONObject): PricingUpdateResult.Success {
        return PricingUpdateResult.Success(
            priceMinor = pricing.getInt("priceMinor"),
            discountMaxMinor = pricing.getInt("discountMaxMinor"),
            minimumPaymentMinor = pricing.getInt("minimumPaymentMinor"),
        )
    }
}
