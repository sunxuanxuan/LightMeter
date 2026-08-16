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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PriceChange
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.lightmeter.app.settings.SharedPreferencesAppSettingsStore
import com.lightmeter.app.ui.theme.LightMeterTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

internal class PaymentMonitorActivity : ComponentActivity() {
    private var notificationAccessGranted by mutableStateOf(false)
    private var monitorConfig by mutableStateOf(
        PaymentMonitorConfig("", "", ""),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PaymentMonitorStore.refresh(applicationContext)
        monitorConfig = PaymentMonitorConfigStore.load(applicationContext)

        setContent {
            val themeStyle = SharedPreferencesAppSettingsStore(applicationContext)
                .load()
                .themeStyle
            val snapshot by PaymentMonitorStore.snapshot.collectAsState()

            LightMeterTheme(themeStyle = themeStyle) {
                PaymentMonitorScreen(
                    notificationAccessGranted = notificationAccessGranted,
                    snapshot = snapshot,
                    monitorConfig = monitorConfig,
                    onOpenNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onSaveConfig = { config ->
                        if (PaymentMonitorConfigStore.save(applicationContext, config)) {
                            monitorConfig = config
                            PaymentUploadScheduler.enqueue(applicationContext)
                            true
                        } else {
                            false
                        }
                    },
                    onRetryUploads = {
                        PaymentUploadScheduler.enqueue(applicationContext)
                    },
                    onUpdatePricing = { priceMinor ->
                        PricingUpdateClient.update(
                            config = monitorConfig,
                            priceMinor = priceMinor,
                        )
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
        monitorConfig = PaymentMonitorConfigStore.load(applicationContext)
        if (
            monitorConfig.isComplete &&
            PaymentMonitorStore.pendingEvents(applicationContext).isNotEmpty()
        ) {
            PaymentUploadScheduler.enqueue(applicationContext)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaymentMonitorScreen(
    notificationAccessGranted: Boolean,
    snapshot: PaymentMonitorSnapshot,
    monitorConfig: PaymentMonitorConfig,
    onOpenNotificationAccess: () -> Unit,
    onSaveConfig: (PaymentMonitorConfig) -> Boolean,
    onRetryUploads: () -> Unit,
    onUpdatePricing: suspend (Int) -> PricingUpdateResult,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MonitorStatusCard(
                notificationAccessGranted = notificationAccessGranted,
                listenerConnectedAt = snapshot.listenerConnectedAt,
                onOpenNotificationAccess = onOpenNotificationAccess,
            )
            MonitorConfigCard(
                config = monitorConfig,
                onSaveConfig = onSaveConfig,
            )
            PricingConfigCard(
                configComplete = monitorConfig.isComplete,
                onUpdatePricing = onUpdatePricing,
            )
            PaymentSummaryCard(
                snapshot = snapshot,
                configComplete = monitorConfig.isComplete,
                onRetryUploads = onRetryUploads,
            )
        }
    }
}

@Composable
private fun PricingConfigCard(
    configComplete: Boolean,
    onUpdatePricing: suspend (Int) -> PricingUpdateResult,
) {
    var priceText by rememberSaveable { mutableStateOf("9.90") }
    var updating by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PriceChange,
                    contentDescription = null,
                )
                Text(
                    text = "官网定价",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedTextField(
                value = priceText,
                onValueChange = { priceText = it },
                label = { Text("商品标价（元）") },
                placeholder = { Text("99.90") },
                supportingText = { Text("随机立减最多 ¥1.00，实付最低 ¥0.01") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value.startsWith("已更新")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Button(
                onClick = {
                    val priceMinor = PricingAmountParser.parseMinorUnits(priceText)
                    if (priceMinor == null) {
                        message = "请输入大于等于 ¥0.01 的有效金额"
                        return@Button
                    }
                    updating = true
                    message = null
                    scope.launch {
                        message = when (
                            val result = onUpdatePricing(priceMinor)
                        ) {
                            is PricingUpdateResult.Success -> {
                                "已更新为 ${formatAmount(result.priceMinor)}，" +
                                    "立减最多 ${formatAmount(result.discountMaxMinor)}，" +
                                    "最低 ${formatAmount(result.minimumPaymentMinor)}"
                            }
                            is PricingUpdateResult.Failure -> result.message
                        }
                        updating = false
                    }
                },
                enabled = configComplete && !updating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (updating) "正在同步" else "更新官网定价")
            }
        }
    }
}

@Composable
private fun MonitorConfigCard(
    config: PaymentMonitorConfig,
    onSaveConfig: (PaymentMonitorConfig) -> Boolean,
) {
    var baseUrl by rememberSaveable(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var monitorId by rememberSaveable(config.monitorId) { mutableStateOf(config.monitorId) }
    var secret by remember(config.secret) { mutableStateOf(config.secret) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                )
                Text(
                    text = "官网回传配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("官网地址") },
                placeholder = { Text("https://meter.example.com") },
                supportingText = {
                    Text("本地联调可填写 http://192.168.1.10:3000")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = monitorId,
                onValueChange = { monitorId = it },
                label = { Text("监听器 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("共享密钥") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (value == "配置已保存") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Button(
                onClick = {
                    val candidate = PaymentMonitorConfig.normalized(
                        baseUrl = baseUrl,
                        monitorId = monitorId,
                        secret = secret,
                    )
                    message = candidate.validate()
                        ?: if (onSaveConfig(candidate)) {
                            "配置已保存"
                        } else {
                            "配置保存失败"
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并重试回传")
            }
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
private fun PaymentSummaryCard(
    snapshot: PaymentMonitorSnapshot,
    configComplete: Boolean,
    onRetryUploads: () -> Unit,
) {
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
                label = "待回传",
                value = "${snapshot.pendingUploadCount} 笔",
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
            Spacer(modifier = Modifier.height(12.dp))
            MonitorValueRow(
                label = "最近回传",
                value = snapshot.lastUploadStatus ?: "暂无",
            )
            if (snapshot.lastUploadAt > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                MonitorValueRow(
                    label = "回传时间",
                    value = formatTimestamp(snapshot.lastUploadAt),
                )
            }
            if (snapshot.pendingUploadCount > 0 && configComplete) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onRetryUploads,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudUpload,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即重试")
                }
            }
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
