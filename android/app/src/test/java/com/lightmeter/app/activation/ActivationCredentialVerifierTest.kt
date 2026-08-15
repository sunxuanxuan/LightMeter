package com.lightmeter.app.activation

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ActivationCredentialVerifierTest {
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val publicKey = privateKey.generatePublicKey().encoded

    @Test
    fun acceptsValidDeviceBoundCredential() {
        val credential = sign(
            """{"deviceID":"A1B2C3D4E5F6A7B8","issuedAt":1000,"version":1}""",
        )

        assertTrue(
            ActivationCredentialVerifier.verify(
                credential = credential,
                expectedDeviceId = "A1B2C3D4E5F6A7B8",
                publicKey = publicKey,
                nowEpochSeconds = 2_000.0,
            ),
        )
    }

    @Test
    fun rejectsCredentialForAnotherDevice() {
        val credential = sign(
            """{"deviceID":"A1B2C3D4E5F6A7B8","expiresAt":null,"issuedAt":1000,"version":1}""",
        )

        assertFalse(
            ActivationCredentialVerifier.verify(
                credential = credential,
                expectedDeviceId = "0000000000000000",
                publicKey = publicKey,
                nowEpochSeconds = 2_000.0,
            ),
        )
    }

    @Test
    fun rejectsExpiredCredential() {
        val credential = sign(
            """{"deviceID":"A1B2C3D4E5F6A7B8","expiresAt":1500,"issuedAt":1000,"version":1}""",
        )

        assertFalse(
            ActivationCredentialVerifier.verify(
                credential = credential,
                expectedDeviceId = "A1B2C3D4E5F6A7B8",
                publicKey = publicKey,
                nowEpochSeconds = 2_000.0,
            ),
        )
    }

    @Test
    fun rejectsTamperedPayload() {
        val credential = sign(
            """{"deviceID":"A1B2C3D4E5F6A7B8","expiresAt":null,"issuedAt":1000,"version":1}""",
        )
        val parts = credential.split('.')
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"deviceID":"0000000000000000","expiresAt":null,"issuedAt":1000,"version":1}"""
                .toByteArray(),
        )

        assertFalse(
            ActivationCredentialVerifier.verify(
                credential = "$tamperedPayload.${parts[1]}",
                expectedDeviceId = "0000000000000000",
                publicKey = publicKey,
                nowEpochSeconds = 2_000.0,
            ),
        )
    }

    private fun sign(payload: String): String {
        val payloadData = payload.toByteArray()
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payloadData, 0, payloadData.size)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString(payloadData)}.${encoder.encodeToString(signer.generateSignature())}"
    }
}
