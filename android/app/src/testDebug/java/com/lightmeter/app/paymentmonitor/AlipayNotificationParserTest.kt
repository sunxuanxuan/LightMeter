package com.lightmeter.app.paymentmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlipayNotificationParserTest {
    @Test
    fun parsesQrCodePaymentNotification() {
        val payment = AlipayNotificationParser.parse(
            content(text = "张三通过扫码向你付款9.90元"),
        )

        assertEquals(990, payment?.amountMinor)
    }

    @Test
    fun parsesOneDecimalAmountAsMinorUnits() {
        val payment = AlipayNotificationParser.parse(
            content(text = "支付宝成功收款9.9元"),
        )

        assertEquals(990, payment?.amountMinor)
    }

    @Test
    fun parsesAmountFromExpandedNotification() {
        val payment = AlipayNotificationParser.parse(
            AlipayNotificationContent(
                title = "支付宝",
                text = null,
                bigText = "收款到账 8.97 元",
                subText = null,
            ),
        )

        assertEquals(897, payment?.amountMinor)
    }

    @Test
    fun acceptsSameAmountFromCompactAndExpandedNotification() {
        val payment = AlipayNotificationParser.parse(
            AlipayNotificationContent(
                title = "支付宝",
                text = "张三通过扫码向你付款9.90元",
                bigText = "张三通过扫码向你付款9.90元，已存入余额",
                subText = null,
            ),
        )

        assertEquals(990, payment?.amountMinor)
    }

    @Test
    fun rejectsNotificationWithoutPaymentKeyword() {
        val payment = AlipayNotificationParser.parse(
            content(text = "余额为9.90元"),
        )

        assertNull(payment)
    }

    @Test
    fun rejectsNotificationWithMultipleAmounts() {
        val payment = AlipayNotificationParser.parse(
            content(text = "成功收款9.90元，账户余额100.00元"),
        )

        assertNull(payment)
    }

    @Test
    fun rejectsAmountWithMoreThanTwoDecimalPlaces() {
        val payment = AlipayNotificationParser.parse(
            content(text = "成功收款9.900元"),
        )

        assertNull(payment)
    }

    @Test
    fun rejectsZeroAmount() {
        val payment = AlipayNotificationParser.parse(
            content(text = "成功收款0.00元"),
        )

        assertNull(payment)
    }

    private fun content(text: String) = AlipayNotificationContent(
        title = "支付宝",
        text = text,
        bigText = null,
        subText = null,
    )
}
