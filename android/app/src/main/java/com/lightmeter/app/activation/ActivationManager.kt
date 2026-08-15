package com.lightmeter.app.activation

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

object ActivationManager {
    private const val PREFS_NAME = "light_meter_activation"
    private const val KEY_CREDENTIAL = "activation_credential"
    private const val LEGACY_KEY_ACTIVATED = "activated"

    fun isActivated(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.contains(LEGACY_KEY_ACTIVATED)) {
            preferences.edit().remove(LEGACY_KEY_ACTIVATED).apply()
        }
        val credential = preferences.getString(KEY_CREDENTIAL, null) ?: return false
        return if (verifyActivationCredential(context, credential)) {
            true
        } else {
            preferences.edit().remove(KEY_CREDENTIAL).apply()
            false
        }
    }

    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "unknown"
        val raw = "$androidId:${Build.MODEL}:${context.packageName}"
        return sha256(raw).take(16)
    }

    fun activate(context: Context, credential: String): Boolean {
        val normalized = credential.trim()
        if (!verifyActivationCredential(context, normalized)) return false
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_KEY_ACTIVATED)
            .putString(KEY_CREDENTIAL, normalized)
            .commit()
    }

    private fun verifyActivationCredential(context: Context, credential: String): Boolean {
        return ActivationCredentialVerifier.verify(
            credential = credential,
            expectedDeviceId = getDeviceId(context),
            publicKey = RELEASE_PUBLIC_KEY,
        )
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
    }

    private val RELEASE_PUBLIC_KEY = Base64.getDecoder().decode(
        "81C0KEAE1R38JV0E5LI5x3tuZgzwUK8FGQv4w62dcBQ=",
    )
}

internal object ActivationCredentialVerifier {
    private const val CREDENTIAL_VERSION = 1
    private const val MAX_CREDENTIAL_LENGTH = 2_048
    private const val MAX_PAYLOAD_LENGTH = 1_024
    private const val PUBLIC_KEY_LENGTH = 32
    private const val SIGNATURE_LENGTH = 64
    private val deviceIdPattern = Regex("^[0-9A-F]{16}$")

    fun verify(
        credential: String,
        expectedDeviceId: String,
        publicKey: ByteArray,
        nowEpochSeconds: Double = System.currentTimeMillis() / 1_000.0,
    ): Boolean {
        if (credential.isEmpty() || credential.length > MAX_CREDENTIAL_LENGTH) return false
        if (!deviceIdPattern.matches(expectedDeviceId)) return false
        if (publicKey.size != PUBLIC_KEY_LENGTH) return false

        val parts = credential.split('.')
        if (parts.size != 2 || parts.any(String::isEmpty)) return false

        return runCatching {
            val payload = Base64.getUrlDecoder().decode(parts[0])
            val signature = Base64.getUrlDecoder().decode(parts[1])
            if (payload.isEmpty() || payload.size > MAX_PAYLOAD_LENGTH) return false
            if (signature.size != SIGNATURE_LENGTH) return false

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(payload, 0, payload.size)
            if (!verifier.verifySignature(signature)) return false

            val claims = JSONObject(payload.toString(Charsets.UTF_8))
            val version = claims.getInt("version")
            val deviceId = claims.getString("deviceID")
            val issuedAt = claims.getDouble("issuedAt")
            val expiresAt = if (claims.isNull("expiresAt")) {
                null
            } else {
                claims.getDouble("expiresAt")
            }

            version == CREDENTIAL_VERSION &&
                deviceId == expectedDeviceId &&
                issuedAt.isFinite() &&
                issuedAt >= 0 &&
                (expiresAt == null || (expiresAt.isFinite() && expiresAt >= nowEpochSeconds))
        }.getOrDefault(false)
    }
}
