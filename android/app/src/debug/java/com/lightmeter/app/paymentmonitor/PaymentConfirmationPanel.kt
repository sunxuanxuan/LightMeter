package com.lightmeter.app.paymentmonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class DecisionPrompt(
    val confirmation: PendingPaymentConfirmation,
    val decision: String,
)

@Composable
internal fun PaymentConfirmationPanel(
    confirmations: List<PendingPaymentConfirmation>,
    loading: Boolean,
    errorMessage: String?,
    actionOrderNo: String?,
    configComplete: Boolean,
    onRefresh: () -> Unit,
    onDecision: (orderNo: String, decision: String) -> Unit,
) {
    var prompt by remember { mutableStateOf<DecisionPrompt?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PendingActions,
                    contentDescription = null,
                )
                Text(
                    text = "待人工确认",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(10.dp),
                    )
                } else {
                    IconButton(
                        onClick = onRefresh,
                        enabled = configComplete,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "刷新待确认订单",
                        )
                    }
                }
            }

            errorMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (confirmations.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (loading) "正在拉取待确认订单" else "暂无待确认订单",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                confirmations.forEachIndexed { index, confirmation ->
                    Spacer(modifier = Modifier.height(16.dp))
                    if (index > 0) {
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    ConfirmationRow(
                        confirmation = confirmation,
                        actionInProgress = actionOrderNo != null,
                        onReject = {
                            prompt = DecisionPrompt(confirmation, "reject")
                        },
                        onConfirm = {
                            prompt = DecisionPrompt(confirmation, "confirm")
                        },
                    )
                }
            }
        }
    }

    prompt?.let { value ->
        val confirming = value.decision == "confirm"
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = {
                Text(if (confirming) "确认已收到付款？" else "确认未收到付款？")
            },
            text = {
                Text(
                    if (confirming) {
                        "确认后官网会立即为订单 ${value.confirmation.orderNo} 生成激活码。"
                    } else {
                        "订单会恢复为待付款状态，用户可以再次提交确认。"
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDecision(
                            value.confirmation.orderNo,
                            value.decision,
                        )
                        prompt = null
                    },
                ) {
                    Text(if (confirming) "确认到账" else "确认驳回")
                }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ConfirmationRow(
    confirmation: PendingPaymentConfirmation,
    actionInProgress: Boolean,
    onReject: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatAmount(confirmation.amountMinor),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (confirmation.notificationObserved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "检测到同额到账",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        ConfirmationValue("订单号", confirmation.orderNo)
        ConfirmationValue("设备 ID", formatDeviceId(confirmation.deviceId))
        ConfirmationValue("找回邮箱", confirmation.email)
        ConfirmationValue("提交时间", formatTimestamp(confirmation.claimedAt))
        ConfirmationValue(
            "确认截止",
            formatTimestamp(confirmation.confirmationExpiresAt),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onReject,
                enabled = !actionInProgress,
                modifier = Modifier.weight(1f),
            ) {
                Text("未收到款")
            }
            Button(
                onClick = onConfirm,
                enabled = !actionInProgress,
                modifier = Modifier.weight(1f),
            ) {
                Text("确认到账")
            }
        }
    }
}

@Composable
private fun ConfirmationValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.7f),
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

private fun formatDeviceId(deviceId: String): String {
    return deviceId.chunked(4).joinToString(" ")
}

private fun formatTimestamp(timestamp: Long): String {
    return DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.MEDIUM,
        Locale.CHINA,
    ).format(Date(timestamp))
}
