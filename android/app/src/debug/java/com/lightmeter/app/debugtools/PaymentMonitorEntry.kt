package com.lightmeter.app.debugtools

import android.content.Context
import android.content.Intent
import com.lightmeter.app.paymentmonitor.PaymentMonitorActivity

object PaymentMonitorEntry {
    const val available = true

    fun open(context: Context) {
        context.startActivity(Intent(context, PaymentMonitorActivity::class.java))
    }
}
