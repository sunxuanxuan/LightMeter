package com.lightmeter.app.paymentmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lightmeter.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class PaymentConfirmationPollingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("正在启动后台订单检查"),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = PaymentMonitorConfigStore.load(applicationContext)
        if (!config.isComplete) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (pollingJob?.isActive != true) {
            pollingJob = serviceScope.launch {
                while (isActive) {
                    updateFromWebsite()
                    delay(BACKGROUND_POLL_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun updateFromWebsite() {
        val config = PaymentMonitorConfigStore.load(applicationContext)
        if (!config.isComplete) {
            stopSelf()
            return
        }
        val message = when (val result = PaymentConfirmationClient.fetch(config)) {
            is ConfirmationQueryResult.Success -> {
                if (result.confirmations.isEmpty()) {
                    "暂无待确认订单，每分钟检查一次"
                } else {
                    "${result.confirmations.size} 笔付款等待人工确认"
                }
            }
            is ConfirmationQueryResult.Failure -> {
                "连接官网失败，稍后自动重试"
            }
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "付款确认后台检查",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "每分钟检查一次网站待确认付款"
            setShowBadge(true)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(message: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("一拍即合订单确认")
            .setContentText(message)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, PaymentMonitorActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    companion object {
        private const val CHANNEL_ID = "payment_confirmation_polling"
        private const val NOTIFICATION_ID = 9021
        private const val BACKGROUND_POLL_INTERVAL_MS = 60_000L

        fun update(context: Context, enabled: Boolean) {
            val intent = Intent(
                context,
                PaymentConfirmationPollingService::class.java,
            )
            if (enabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
