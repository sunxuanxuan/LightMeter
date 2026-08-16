package com.lightmeter.app.paymentmonitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class PaymentMonitorSnapshot(
    val listenerConnectedAt: Long = 0,
    val lastAmountMinor: Int? = null,
    val lastObservedAt: Long = 0,
    val observedPaymentCount: Int = 0,
    val pendingUploadCount: Int = 0,
    val lastUploadAt: Long = 0,
    val lastUploadStatus: String? = null,
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
    private const val KEY_PENDING_EVENTS = "pending_events"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
    private const val KEY_LAST_UPLOAD_STATUS = "last_upload_status"
    private const val MAX_RECENT_EVENT_IDS = 100
    private const val MAX_PENDING_EVENTS = 100

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
        event: PendingPaymentEvent,
    ): RecordEventResult {
        val preferences = preferences(context)
        val recentEventIds = preferences.getString(KEY_RECENT_EVENT_IDS, "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toMutableList()

        if (event.eventId in recentEventIds) {
            return RecordEventResult.DUPLICATE
        }

        val pendingEvents = readPendingEvents(preferences.getString(KEY_PENDING_EVENTS, "[]"))
            .toMutableList()
        if (pendingEvents.size >= MAX_PENDING_EVENTS) {
            return RecordEventResult.FAILED
        }
        pendingEvents += event
        recentEventIds += event.eventId

        val saved = preferences.edit()
            .putInt(KEY_LAST_AMOUNT_MINOR, event.amountMinor)
            .putLong(KEY_LAST_OBSERVED_AT, event.observedAt)
            .putInt(
                KEY_OBSERVED_PAYMENT_COUNT,
                preferences.getInt(KEY_OBSERVED_PAYMENT_COUNT, 0) + 1,
            )
            .putString(
                KEY_RECENT_EVENT_IDS,
                recentEventIds.takeLast(MAX_RECENT_EVENT_IDS).joinToString("\n"),
            )
            .putString(KEY_PENDING_EVENTS, encodePendingEvents(pendingEvents))
            .commit()

        if (!saved) return RecordEventResult.FAILED
        mutableSnapshot.value = readSnapshot(context)
        return RecordEventResult.RECORDED
    }

    @Synchronized
    fun pendingEvents(context: Context): List<PendingPaymentEvent> {
        return readPendingEvents(
            preferences(context).getString(KEY_PENDING_EVENTS, "[]"),
        )
    }

    @Synchronized
    fun markUploadComplete(
        context: Context,
        eventId: String,
        processStatus: String,
        completedAt: Long,
    ): Boolean {
        val preferences = preferences(context)
        val retainedEvents = readPendingEvents(
            preferences.getString(KEY_PENDING_EVENTS, "[]"),
        ).filterNot { it.eventId == eventId }
        val saved = preferences.edit()
            .putString(KEY_PENDING_EVENTS, encodePendingEvents(retainedEvents))
            .putLong(KEY_LAST_UPLOAD_AT, completedAt)
            .putString(KEY_LAST_UPLOAD_STATUS, processStatus)
            .commit()
        if (saved) mutableSnapshot.value = readSnapshot(context)
        return saved
    }

    @Synchronized
    fun markUploadFailure(context: Context, status: String, failedAt: Long) {
        val preferences = preferences(context)
        if (
            preferences.edit()
                .putLong(KEY_LAST_UPLOAD_AT, failedAt)
                .putString(KEY_LAST_UPLOAD_STATUS, status)
                .commit()
        ) {
            mutableSnapshot.value = readSnapshot(context)
        }
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
            pendingUploadCount = readPendingEvents(
                preferences.getString(KEY_PENDING_EVENTS, "[]"),
            ).size,
            lastUploadAt = preferences.getLong(KEY_LAST_UPLOAD_AT, 0),
            lastUploadStatus = preferences.getString(KEY_LAST_UPLOAD_STATUS, null),
        )
    }

    private fun readPendingEvents(value: String?): List<PendingPaymentEvent> {
        return runCatching {
            val array = JSONArray(value ?: "[]")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PendingPaymentEvent(
                            eventId = item.getString("eventId"),
                            amountMinor = item.getInt("amountMinor"),
                            observedAt = item.getLong("observedAt"),
                            notificationHash = item.getString("notificationHash"),
                            monitorVersion = item.getString("monitorVersion"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodePendingEvents(events: List<PendingPaymentEvent>): String {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("eventId", event.eventId)
                    .put("amountMinor", event.amountMinor)
                    .put("observedAt", event.observedAt)
                    .put("notificationHash", event.notificationHash)
                    .put("monitorVersion", event.monitorVersion),
            )
        }
        return array.toString()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
