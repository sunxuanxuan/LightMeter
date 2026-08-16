package com.lightmeter.app.paymentmonitor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PaymentMonitorConfig(
    val baseUrl: String,
    val monitorId: String,
    val secret: String,
) {
    val endpointUrl: String
        get() = "$baseUrl/api/internal/payment-monitor/events"

    val isComplete: Boolean
        get() = validate() == null

    fun validate(): String? {
        val normalizedUri = runCatching { URI(baseUrl) }.getOrNull()
            ?: return "官网地址格式不正确"
        if (normalizedUri.scheme !in setOf("https", "http")) {
            return "官网地址必须以 https:// 或 http:// 开头"
        }
        if (normalizedUri.host.isNullOrBlank()) {
            return "官网地址缺少域名或 IP"
        }
        if (normalizedUri.rawQuery != null || normalizedUri.rawFragment != null) {
            return "官网地址不能包含查询参数或锚点"
        }
        if (normalizedUri.userInfo != null) {
            return "官网地址不能包含用户名或密码"
        }
        if (normalizedUri.path !in setOf("", "/")) {
            return "官网地址不要包含接口路径"
        }
        if (!MONITOR_ID_PATTERN.matches(monitorId)) {
            return "监听器 ID 需为 8-128 位字母、数字、下划线或连字符"
        }
        if (secret.length < 32) {
            return "共享密钥至少需要 32 个字符"
        }
        return null
    }

    companion object {
        private val MONITOR_ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,128}$")

        fun normalized(
            baseUrl: String,
            monitorId: String,
            secret: String,
        ) = PaymentMonitorConfig(
            baseUrl = baseUrl.trim().trimEnd('/'),
            monitorId = monitorId.trim(),
            secret = secret.trim(),
        )
    }
}

internal object PaymentMonitorConfigStore {
    private const val PREFS_NAME = "payment_monitor_config_debug"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MONITOR_ID = "monitor_id"
    private const val KEY_ENCRYPTED_SECRET = "encrypted_secret"

    fun load(context: Context): PaymentMonitorConfig {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PaymentMonitorConfig.normalized(
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            monitorId = preferences.getString(KEY_MONITOR_ID, "").orEmpty(),
            secret = preferences.getString(KEY_ENCRYPTED_SECRET, null)
                ?.let(PaymentMonitorSecretCipher::decrypt)
                .orEmpty(),
        )
    }

    fun save(context: Context, config: PaymentMonitorConfig): Boolean {
        if (!config.isComplete) return false
        return runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BASE_URL, config.baseUrl)
                .putString(KEY_MONITOR_ID, config.monitorId)
                .putString(
                    KEY_ENCRYPTED_SECRET,
                    PaymentMonitorSecretCipher.encrypt(config.secret),
                )
                .commit()
        }.getOrDefault(false)
    }
}

private object PaymentMonitorSecretCipher {
    private const val KEY_ALIAS = "payment_monitor_debug_config"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, encrypted)
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    fun decrypt(value: String): String? {
        return runCatching {
            val parts = value.split(".")
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv),
            )
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }
}
