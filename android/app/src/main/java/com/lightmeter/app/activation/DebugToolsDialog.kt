package com.lightmeter.app.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DebugToolsDialog(
    onDismiss: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var deviceIdInput by remember { mutableStateOf("") }
    var generatedCode by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "激活码生成器",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "输入设备 ID",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Device ID input
        BasicTextField(
            value = deviceIdInput,
            onValueChange = { newValue ->
                val filtered = newValue.filter { it in "0123456789ABCDEFabcdef" }
                if (filtered.length <= 16) {
                    deviceIdInput = filtered.uppercase()
                    generatedCode = null
                    errorMessage = null
                }
            },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp,
            ),
            singleLine = true,
            decorationBox = { innerTextField ->
                if (deviceIdInput.isEmpty()) {
                    Text(
                        text = "例如: ABBF0883E57DE7A0",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            textAlign = TextAlign.Center,
                            letterSpacing = 2.sp,
                        ),
                    )
                }
                innerTextField()
            },
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (deviceIdInput.length != 16) {
                    errorMessage = "请输入完整的 16 位设备 ID"
                    generatedCode = null
                    return@Button
                }
                try {
                    generatedCode = formatCode(ActivationManager.generateCode(deviceIdInput))
                    errorMessage = null
                } catch (e: Exception) {
                    errorMessage = "生成失败: ${e.message}"
                    generatedCode = null
                }
            },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("生成激活码")
        }

        errorMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        generatedCode?.let { code ->
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "激活码",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = code,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 2.sp,
                    ),
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code.replace("-", "")))
                        copied = true
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "复制激活码",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (copied) {
                Text(
                    text = "已复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("关闭")
        }
    }
}

private fun formatCode(code: String): String {
    return code.chunked(4).joinToString("-")
}