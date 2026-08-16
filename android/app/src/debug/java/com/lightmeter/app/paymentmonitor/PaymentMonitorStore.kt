package com.lightmeter.app.paymentmonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PaymentMonitorSnapshot(
    val listenerConnectedAt: Long = 0,
    val lastAmountMinor: Int? = null,
    val lastObservedAt: Long = 0,
    val observedPaymentCount: Int = 0,
)

internal enum class RecordEventResult {
    RECORDED,
    DUPLICATE,
    FAILED,
}

internal object PaymentMonitorStore {
    private const val PREFS_NAME = "payment_monitor_debug"
    private const val KEY_LISTENER_CONNECTED_AT = "listener_connected_at"
    private const val KEY_LAST_AMOUNT_MINOR = "last_amount_minor"
    private const val KEY_LAST_OBSERVED_AT = "last_observed_at"
    private const val KEY_OBSERVED_PAYMENT_COUNT = "observed_payment_count"
    private const val KEY_RECENT_EVENT_IDS = "recent_event_ids"
    private const val MAX_RECENT_EVENT_IDS = 100

    private val mutableSnapshot = MutableStateFlow(PaymentMonitorSnapshot())

    val snapshot: StateFlow<PaymentMonitorSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun refresh(context: Context) {
        mutableSnapshot.value = readSnapshot(context)
    }

    @Synchronized
    fun markListenerConnected(context: Context, connectedAt: Long) {
        val preferences = preferences(context)
        if (preferences.edit().putLong(KEY_LISTENER_CONNECTED_AT, connectedAt).commit()) {
            mutableSnapshot.value = readSnapshot(context)
        }
    }

    @Synchronized
    fun recordPayment(
        context: Context,
        eventId: String,
        amountMinor: Int,
        observedAt: Long,
    ): RecordEventResult {
        val preferences = preferences(context)
        val recentEventIds = preferences.getString(KEY_RECENT_EVENT_IDS, "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toMutableList()

        if (eventId in recentEventIds) {
            return RecordEventResult.DUPLICATE
        }

        recentEventIds += eventId
        val retainedEventIds = recentEventIds.takeLast(MAX_RECENT_EVENT_IDS)
        val count = preferences.getInt(KEY_OBSERVED_PAYMENT_COUNT, 0) + 1
        val saved = preferences.edit()
            .putInt(KEY_LAST_AMOUNT_MINOR, amountMinor)
            .putLong(KEY_LAST_OBSERVED_AT, observedAt)
            .putInt(KEY_OBSERVED_PAYMENT_COUNT, count)
            .putString(KEY_RECENT_EVENT_IDS, retainedEventIds.joinToString("\n"))
            .commit()

        if (!saved) return RecordEventResult.FAILED

        mutableSnapshot.value = readSnapshot(context)
        return RecordEventResult.RECORDED
    }

    private fun readSnapshot(context: Context): PaymentMonitorSnapshot {
        val preferences = preferences(context)
        return PaymentMonitorSnapshot(
            listenerConnectedAt = preferences.getLong(KEY_LISTENER_CONNECTED_AT, 0),
            lastAmountMinor = if (preferences.contains(KEY_LAST_AMOUNT_MINOR)) {
                preferences.getInt(KEY_LAST_AMOUNT_MINOR, 0)
            } else {
                null
            },
            lastObservedAt = preferences.getLong(KEY_LAST_OBSERVED_AT, 0),
            observedPaymentCount = preferences.getInt(KEY_OBSERVED_PAYMENT_COUNT, 0),
        )
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
