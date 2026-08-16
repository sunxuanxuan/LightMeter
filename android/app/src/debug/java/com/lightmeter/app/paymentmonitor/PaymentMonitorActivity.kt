package com.lightmeter.app.paymentmonitor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.lightmeter.app.settings.SharedPreferencesAppSettingsStore
import com.lightmeter.app.ui.theme.LightMeterTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal class PaymentMonitorActivity : ComponentActivity() {
    private var notificationAccessGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PaymentMonitorStore.refresh(applicationContext)

        setContent {
            val themeStyle = SharedPreferencesAppSettingsStore(applicationContext)
                .load()
                .themeStyle
            val snapshot by PaymentMonitorStore.snapshot.collectAsState()

            LightMeterTheme(themeStyle = themeStyle) {
                PaymentMonitorScreen(
                    notificationAccessGranted = notificationAccessGranted,
                    snapshot = snapshot,
                    onOpenNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationAccessGranted =
            NotificationManagerCompat.getEnabledListenerPackages(this)
                .contains(packageName)
        PaymentMonitorStore.refresh(applicationContext)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentMonitorScreen(
    notificationAccessGranted: Boolean,
    snapshot: PaymentMonitorSnapshot,
    onOpenNotificationAccess: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("支付宝订单监听") },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MonitorStatusCard(
                notificationAccessGranted = notificationAccessGranted,
                listenerConnectedAt = snapshot.listenerConnectedAt,
                onOpenNotificationAccess = onOpenNotificationAccess,
            )
            PaymentSummaryCard(snapshot = snapshot)
        }
    }
}

@Composable
private fun MonitorStatusCard(
    notificationAccessGranted: Boolean,
    listenerConnectedAt: Long,
    onOpenNotificationAccess: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                )
                Column {
                    Text(
                        text = if (notificationAccessGranted) {
                            "通知访问已开启"
                        } else {
                            "通知访问未开启"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (listenerConnectedAt > 0) {
                            "最近连接 ${formatTimestamp(listenerConnectedAt)}"
                        } else {
                            "等待监听服务连接"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!notificationAccessGranted) {
                Button(onClick = onOpenNotificationAccess) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("打开通知访问设置")
                }
            }
        }
    }
}

@Composable
private fun PaymentSummaryCard(snapshot: PaymentMonitorSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Payments,
                    contentDescription = null,
                )
                Text(
                    text = "到账记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            MonitorValueRow(
                label = "累计识别",
                value = "${snapshot.observedPaymentCount} 笔",
            )
            Spacer(modifier = Modifier.height(12.dp))
            MonitorValueRow(
                label = "最近金额",
                value = snapshot.lastAmountMinor?.let(::formatAmount) ?: "暂无",
            )
            Spacer(modifier = Modifier.height(12.dp))
            MonitorValueRow(
                label = "最近时间",
                value = snapshot.lastObservedAt
                    .takeIf { it > 0 }
                    ?.let(::formatTimestamp)
                    ?: "暂无",
            )
        }
    }
}

@Composable
private fun MonitorValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatAmount(amountMinor: Int): String {
    return String.format(
        Locale.CHINA,
        "¥%d.%02d",
        amountMinor / 100,
        amountMinor % 100,
    )
}

private fun formatTimestamp(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.MEDIUM,
        Locale.CHINA,
    ).format(Date(timestamp))
}
