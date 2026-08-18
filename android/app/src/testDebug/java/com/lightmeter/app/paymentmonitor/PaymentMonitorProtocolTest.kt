package com.lightmeter.app.paymentmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentMonitorProtocolTest {
    @Test
    fun signsRequestExactlyLikeWebsiteProtocol() {
        val request = PaymentMonitorProtocol.createSignedRequest(
            event = PendingPaymentEvent(
                eventId = "event_1234567890123456",
                amountMinor = 987,
                observedAt = 1_786_812_345_000,
                notificationHash =
                    "0123456789abcdef0123456789abcdef" +
                        "0123456789abcdef0123456789abcdef",
                monitorVersion = "0.1.0-debug",
            ),
            secret = "test-secret-with-at-least-32-characters",
            timestamp = 1_786_812_345_999,
            nonce = "nonce_1234567890abcdef",
        )

        assertEquals(
            "0881d7d858ec6df56c5f23bbf7bfc621" +
                "29e900dc4e24364528903b33f55b7dfb",
            request.signature,
        )
        assertTrue(request.body.contains("\"amountMinor\":987"))
        assertEquals("1786812345999", request.timestamp)
    }

    @Test
    fun acceptsWebsiteAddressWithExplicitPort() {
        val config = PaymentMonitorConfig.normalized(
            baseUrl = "http://192.168.1.10:3000/",
            monitorId = "monitor_01",
            secret = "0123456789abcdef0123456789abcdef",
        )

        assertNull(config.validate())
        assertEquals(
            "http://192.168.1.10:3000/api/internal/payment-monitor/events",
            config.endpointUrl,
        )
    }

    @Test
    fun signsPricingUpdateExactlyLikeWebsiteProtocol() {
        val request = PaymentMonitorProtocol.createPricingUpdateRequest(
            priceMinor = 9_990,
            monitorVersion = "0.1.0-debug",
            secret = "test-secret-with-at-least-32-characters",
            timestamp = 1_786_812_345_999,
            nonce = "nonce_1234567890abcdef",
        )

        assertEquals(
            "07e3c99791d1ce7b96266d0d3a45321" +
                "a801363e35c8ff65c27e18d92cd497ef7",
            request.signature,
        )
        assertTrue(request.body.contains("\"priceMinor\":9990"))
    }

    @Test
    fun signsPricingQueryExactlyLikeWebsiteProtocol() {
        val request = PaymentMonitorProtocol.createPricingQueryRequest(
            secret = "test-secret-with-at-least-32-characters",
            timestamp = 1_786_812_345_999,
            nonce = "nonce_1234567890abcdef",
        )

        assertEquals(
            "f8c609f6830a09dc84e6b32740b1e735" +
                "1f88ab0567fee46dd2369b63bf282aea",
            request.signature,
        )
        assertEquals("", request.body)
    }

    @Test
    fun signsConfirmationQueryExactlyLikeWebsiteProtocol() {
        val request = PaymentMonitorProtocol.createConfirmationQueryRequest(
            secret = "test-secret-with-at-least-32-characters",
            timestamp = 1_786_812_345_999,
            nonce = "nonce_1234567890abcdef",
        )

        assertEquals(
            "c0bb97ba3e1403b39ae70896533ad338" +
                "48d783477d4ecdd083e4ef460df36531",
            request.signature,
        )
        assertEquals("", request.body)
    }

    @Test
    fun signsConfirmationDecisionExactlyLikeWebsiteProtocol() {
        val (path, request) =
            PaymentMonitorProtocol.createConfirmationDecisionRequest(
                orderNo = "FLM-20260818-ABCDEF12",
                decision = "confirm",
                monitorVersion = "0.1.0-debug",
                secret = "test-secret-with-at-least-32-characters",
                timestamp = 1_786_812_345_999,
                nonce = "nonce_1234567890abcdef",
            )

        assertEquals(
            "/api/internal/payment-confirmations/" +
                "FLM-20260818-ABCDEF12/decision",
            path,
        )
        assertEquals(
            "ed37845ec751af3dac83c0bf5aa591813" +
                "d10230db6db37f4bafe688fa97f2497",
            request.signature,
        )
        assertTrue(request.body.contains("\"decision\":\"confirm\""))
    }

    @Test
    fun parsesPricingWithOneDecimalPlace() {
        assertEquals(9_990, PricingAmountParser.parseMinorUnits("99.9"))
    }

    @Test
    fun keepsPricingMinimumAtOneCent() {
        assertEquals(1, PricingAmountParser.parseMinorUnits("0.01"))
        assertEquals(null, PricingAmountParser.parseMinorUnits("0"))
    }

    @Test
    fun rejectsWebsiteAddressContainingApiPath() {
        val config = PaymentMonitorConfig.normalized(
            baseUrl = "https://meter.example.com/api",
            monitorId = "monitor_01",
            secret = "0123456789abcdef0123456789abcdef",
        )

        assertEquals("官网地址不要包含接口路径", config.validate())
    }

    @Test
    fun rejectsShortSharedSecret() {
        val config = PaymentMonitorConfig.normalized(
            baseUrl = "https://meter.example.com",
            monitorId = "monitor_01",
            secret = "short-secret",
        )

        assertEquals("共享密钥至少需要 32 个字符", config.validate())
    }
}
