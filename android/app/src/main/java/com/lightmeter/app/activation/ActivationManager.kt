package com.lightmeter.app.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Offline device-bound activation using HMAC-SHA256.
 *
 * Each activation code is tied to a specific device fingerprint.
 * The secret key is embedded in the app and obfuscated by R8/ProGuard.
 */
object ActivationManager {

    private const val PREFS_NAME = "light_meter_activation"
    private const val KEY_ACTIVATED = "activated"

    // Embedded secret — obfuscated by R8 in release builds.
    // Change this per distribution batch if needed.
    private val SECRET = byteArrayOf(
        0x46, 0x69, 0x6C, 0x6D, 0x4C, 0x69, 0x67, 0x68,
        0x74, 0x4D, 0x65, 0x74, 0x65, 0x72, 0x2D, 0x41,
        0x63, 0x74, 0x69, 0x76, 0x61, 0x74, 0x69, 0x6F,
        0x6E, 0x2D, 0x53, 0x65, 0x63, 0x72, 0x65, 0x74,
    )

    fun isActivated(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVATED, false)
    }

    /**
     * Returns a 16-char hex device fingerprint shown to the user.
     * The user sends this to the developer to receive an activation code.
     */
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "unknown"
        val model = Build.MODEL
        val raw = "$androidId:$model:${context.packageName}"
        return sha256(raw).take(16)
    }

    /**
     * Verifies an activation code against this device.
     * Accepts codes with or without dashes/spaces.
     */
    fun verifyActivationCode(context: Context, code: String): Boolean {
        val deviceId = getDeviceId(context)
        val normalized = code.replace("-", "").replace(" ", "").uppercase()
        if (normalized.length != 16) return false
        if (!normalized.all { it in "0123456789ABCDEF" }) return false

        val expected = generateCode(deviceId)
        return normalized == expected
    }

    fun setActivated(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVATED, true)
            .apply()
    }

    /** Generates the expected activation code for a given device ID. */
    internal fun generateCode(deviceId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(SECRET, "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(deviceId.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02X".format(it) }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }
}