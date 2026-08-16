package com.lightmeter.app.paymentmonitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal data class MonitorHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal object PaymentMonitorHttpClient {
    suspend fun post(
        config: PaymentMonitorConfig,
        path: String,
        request: SignedMonitorRequest,
    ): MonitorHttpResponse = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val bodyBytes = request.body.toByteArray(Charsets.UTF_8)
            connection = (
                URL("${config.baseUrl}$path").openConnection() as HttpURLConnection
                ).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                useCaches = false
                setFixedLengthStreamingMode(bodyBytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Monitor-Id", config.monitorId)
                setRequestProperty("X-Timestamp", request.timestamp)
                setRequestProperty("X-Nonce", request.nonce)
                setRequestProperty("X-Signature", request.signature)
                setRequestProperty("User-Agent", "FilmLightMeter-Debug-Monitor")
            }
            connection.outputStream.use { it.write(bodyBytes) }
            val statusCode = connection.responseCode
            val responseBody = if (statusCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            MonitorHttpResponse(statusCode, responseBody)
        } finally {
            connection?.disconnect()
        }
    }
}
