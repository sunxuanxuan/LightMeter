package com.lightmeter.app.paymentmonitor

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class PaymentUploadWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val config = PaymentMonitorConfigStore.load(applicationContext)
        config.validate()?.let { error ->
            PaymentMonitorStore.markUploadFailure(
                context = applicationContext,
                status = "配置无效：$error",
                failedAt = System.currentTimeMillis(),
            )
            return Result.failure()
        }

        for (event in PaymentMonitorStore.pendingEvents(applicationContext)) {
            when (val result = upload(config, event)) {
                is UploadResult.Accepted -> {
                    if (
                        !PaymentMonitorStore.markUploadComplete(
                            context = applicationContext,
                            eventId = event.eventId,
                            processStatus = result.processStatus,
                            completedAt = System.currentTimeMillis(),
                        )
                    ) {
                        return Result.retry()
                    }
                }

                is UploadResult.Retryable -> {
                    PaymentMonitorStore.markUploadFailure(
                        context = applicationContext,
                        status = result.message,
                        failedAt = System.currentTimeMillis(),
                    )
                    return if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }

                is UploadResult.PermanentRejection -> {
                    if (
                        !PaymentMonitorStore.markUploadComplete(
                            context = applicationContext,
                            eventId = event.eventId,
                            processStatus = result.message,
                            completedAt = System.currentTimeMillis(),
                        )
                    ) {
                        return Result.retry()
                    }
                }

                is UploadResult.Rejected -> {
                    PaymentMonitorStore.markUploadFailure(
                        context = applicationContext,
                        status = result.message,
                        failedAt = System.currentTimeMillis(),
                    )
                    return Result.failure()
                }
            }
        }
        return Result.success()
    }

    private suspend fun upload(
        config: PaymentMonitorConfig,
        event: PendingPaymentEvent,
    ): UploadResult {
        val request = PaymentMonitorProtocol.createSignedRequest(
            event = event,
            secret = config.secret,
        )
        return try {
            val response = PaymentMonitorHttpClient.post(
                config = config,
                path = PaymentMonitorProtocol.EVENTS_PATH,
                request = request,
            )
            when {
                response.statusCode in 200..299 -> parseAcceptedResponse(response.body)
                response.statusCode == 408 ||
                    response.statusCode == 429 ||
                    response.statusCode >= 500 ->
                    UploadResult.Retryable(
                        "官网暂不可用（HTTP ${response.statusCode}）",
                    )
                response.statusCode in setOf(401, 403, 404) ->
                    UploadResult.Rejected(
                        "请检查官网配置（HTTP ${response.statusCode}）",
                    )
                else -> UploadResult.PermanentRejection(
                    "事件已拒绝（HTTP ${response.statusCode}）",
                )
            }
        } catch (error: Exception) {
            Log.w(TAG, "Payment event upload failed: ${error.javaClass.simpleName}")
            UploadResult.Retryable("网络发送失败")
        }
    }

    private fun parseAcceptedResponse(responseBody: String): UploadResult {
        return runCatching {
            val response = JSONObject(responseBody)
            check(response.optBoolean("accepted", false))
            val processStatus = response.getString("processStatus")
            check(processStatus in TERMINAL_PROCESS_STATUSES)
            UploadResult.Accepted(processStatus)
        }.getOrElse {
            UploadResult.Retryable("官网响应格式不正确")
        }
    }

    private sealed interface UploadResult {
        data class Accepted(val processStatus: String) : UploadResult
        data class Retryable(val message: String) : UploadResult
        data class PermanentRejection(val message: String) : UploadResult
        data class Rejected(val message: String) : UploadResult
    }

    private companion object {
        const val TAG = "PaymentMonitor"
        const val MAX_RETRY_ATTEMPTS = 10
        val TERMINAL_PROCESS_STATUSES = setOf(
            "matched",
            "duplicate",
            "unmatched",
            "manual_review",
        )
    }
}

internal object PaymentUploadScheduler {
    private const val UNIQUE_WORK_NAME = "payment-monitor-event-upload"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<PaymentUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS,
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
    }
}
