package com.lightmeter.app.paymentmonitor

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class PendingPaymentEvent(
    val eventId: String,
    val amountMinor: Int,
    val observedAt: Long,
    val notificationHash: String,
    val monitorVersion: String,
)

internal data class SignedMonitorRequest(
    val timestamp: String,
    val nonce: String,
    val body: String,
    val signature: String,
)

internal object PaymentMonitorProtocol {
    const val EVENTS_PATH = "/api/internal/payment-monitor/events"
    const val PRICING_PATH = "/api/internal/payment-monitor/pricing"

    fun createSignedRequest(
        event: PendingPaymentEvent,
        secret: String,
        timestamp: Long = System.currentTimeMillis(),
        nonce: String = UUID.randomUUID().toString().replace("-", ""),
    ): SignedMonitorRequest {
        val body = buildString {
            append("{\"eventId\":")
            append(JSONObject.quote(event.eventId))
            append(",\"channel\":\"alipay\"")
            append(",\"amountMinor\":")
            append(event.amountMinor)
            append(",\"observedAt\":")
            append(event.observedAt)
            append(",\"notificationHash\":")
            append(JSONObject.quote(event.notificationHash))
            append(",\"monitorVersion\":")
            append(JSONObject.quote(event.monitorVersion))
            append('}')
        }
        return createSignedRequest(
            path = EVENTS_PATH,
            body = body,
            secret = secret,
            timestamp = timestamp,
            nonce = nonce,
        )
    }

    fun createPricingUpdateRequest(
        priceMinor: Int,
        monitorVersion: String,
        secret: String,
        timestamp: Long = System.currentTimeMillis(),
        nonce: String = UUID.randomUUID().toString().replace("-", ""),
    ): SignedMonitorRequest {
        require(priceMinor >= 1)
        val body = buildString {
            append("{\"priceMinor\":")
            append(priceMinor)
            append(",\"monitorVersion\":")
            append(JSONObject.quote(monitorVersion))
            append('}')
        }
        return createSignedRequest(
            path = PRICING_PATH,
            body = body,
            secret = secret,
            timestamp = timestamp,
            nonce = nonce,
        )
    }

    private fun createSignedRequest(
        path: String,
        body: String,
        secret: String,
        timestamp: Long,
        nonce: String,
    ): SignedMonitorRequest {
        val timestampText = timestamp.toString()
        val payload = listOf(
            "POST",
            path,
            timestampText,
            nonce,
            sha256(body),
        ).joinToString("\n")
        return SignedMonitorRequest(
            timestamp = timestampText,
            nonce = nonce,
            body = body,
            signature = hmacSha256(payload, secret),
        )
    }

    fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hmacSha256(value: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
