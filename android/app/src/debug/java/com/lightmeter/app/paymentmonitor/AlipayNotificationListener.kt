package com.lightmeter.app.paymentmonitor

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.lightmeter.app.BuildConfig
import java.security.MessageDigest
import java.util.Locale

internal class AlipayNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        PaymentMonitorStore.markListenerConnected(
            context = applicationContext,
            connectedAt = System.currentTimeMillis(),
        )
        if (
            PaymentMonitorConfigStore.load(applicationContext).isComplete &&
            PaymentMonitorStore.pendingEvents(applicationContext).isNotEmpty()
        ) {
            PaymentUploadScheduler.enqueue(applicationContext)
        }
        Log.i(TAG, "Alipay notification listener connected")
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification?) {
        val notification = statusBarNotification ?: return
        if (notification.packageName != ALIPAY_PACKAGE_NAME) return

        val extras = notification.notification.extras
        val parsed = AlipayNotificationParser.parse(
            AlipayNotificationContent(
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            ),
        ) ?: return

        val observedAt = notification.postTime.takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val eventId = createEventId(
            notificationKey = notification.key,
            observedAt = observedAt,
            amountMinor = parsed.amountMinor,
            normalizedContent = parsed.normalizedContent,
        )

        when (
            PaymentMonitorStore.recordPayment(
                context = applicationContext,
                event = PendingPaymentEvent(
                    eventId = eventId,
                    amountMinor = parsed.amountMinor,
                    observedAt = observedAt,
                    notificationHash = sha256(parsed.normalizedContent),
                    monitorVersion = BuildConfig.VERSION_NAME,
                ),
            )
        ) {
            RecordEventResult.RECORDED -> {
                PaymentUploadScheduler.enqueue(applicationContext)
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "Alipay payment observed: amountMinor=%d eventId=%s",
                        parsed.amountMinor,
                        eventId.take(12),
                    ),
                )
            }

            RecordEventResult.DUPLICATE -> {
                Log.d(TAG, "Duplicate Alipay notification ignored")
            }

            RecordEventResult.FAILED -> {
                Log.e(TAG, "Unable to persist Alipay payment event")
            }
        }
    }

    private fun createEventId(
        notificationKey: String,
        observedAt: Long,
        amountMinor: Int,
        normalizedContent: String,
    ): String {
        val contentHash = sha256(normalizedContent)
        return sha256("$notificationKey\n$observedAt\n$amountMinor\n$contentHash")
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val TAG = "PaymentMonitor"
        const val ALIPAY_PACKAGE_NAME = "com.eg.android.AlipayGphone"
    }
}
