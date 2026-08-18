package com.lightmeter.app.paymentmonitor

internal data class AlipayNotificationContent(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
)

internal data class ParsedAlipayPayment(
    val amountMinor: Int,
    val normalizedContent: String,
)

internal object AlipayNotificationParser {
    private val paymentKeywords = listOf(
        "通过扫码向你付款",
        "成功收款",
        "收款到账",
        "已收款",
        "收款通知",
    )
    private val amountPattern =
        Regex("""(?<![0-9.])([0-9]{1,7})(?:\.([0-9]{1,2}))?\s*元""")

    fun parse(content: AlipayNotificationContent): ParsedAlipayPayment? {
        val normalizedParts = listOf(
            content.title,
            content.text,
            content.bigText,
            content.subText,
        ).mapNotNull { value ->
            value
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }.distinct()

        val normalizedContent = normalizedParts.joinToString(" | ")
        if (normalizedContent.isEmpty()) return null
        if (paymentKeywords.none(normalizedContent::contains)) return null

        val amounts = amountPattern.findAll(normalizedContent)
            .mapNotNull(::amountToMinor)
            .distinct()
            .toList()
        if (amounts.size != 1) return null

        return ParsedAlipayPayment(
            amountMinor = amounts.single(),
            normalizedContent = normalizedContent,
        )
    }

    private fun amountToMinor(match: MatchResult): Int? {
        val yuan = match.groupValues[1].toIntOrNull() ?: return null
        val cents = match.groupValues[2].padEnd(2, '0').ifEmpty { "00" }
            .toIntOrNull() ?: return null
        val amountMinor = yuan * 100 + cents
        return amountMinor.takeIf { it > 0 }
    }
}
