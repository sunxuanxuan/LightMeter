import CryptoKit
import Foundation
import XCTest
@testable import FilmLightMeter

final class ActivationCredentialVerifierTests: XCTestCase {
    private let deviceID = "A1B2C3D4E5F6A7B8"

    func testAcceptsValidDeviceBoundCredential() throws {
        let key = Curve25519.Signing.PrivateKey()
        let credential = try makeCredential(key: key, expiresAt: nil)

        XCTAssertTrue(
            ActivationCredentialVerifier.verify(
                credential: credential,
                expectedDeviceID: deviceID,
                publicKeyData: key.publicKey.rawRepresentation,
                now: 2_000
            )
        )
    }

    func testRejectsCredentialForAnotherDevice() throws {
        let key = Curve25519.Signing.PrivateKey()
        let credential = try makeCredential(key: key, expiresAt: nil)

        XCTAssertFalse(
            ActivationCredentialVerifier.verify(
                credential: credential,
                expectedDeviceID: "0000000000000000",
                publicKeyData: key.publicKey.rawRepresentation,
                now: 2_000
            )
        )
    }

    func testRejectsExpiredCredential() throws {
        let key = Curve25519.Signing.PrivateKey()
        let credential = try makeCredential(key: key, expiresAt: 1_500)

        XCTAssertFalse(
            ActivationCredentialVerifier.verify(
                credential: credential,
                expectedDeviceID: deviceID,
                publicKeyData: key.publicKey.rawRepresentation,
                now: 2_000
            )
        )
    }

    func testRejectsTamperedPayload() throws {
        let key = Curve25519.Signing.PrivateKey()
        let credential = try makeCredential(key: key, expiresAt: nil)
        let signature = credential.split(separator: ".")[1]
        let claims = ActivationClaims(
            version: 1,
            deviceID: "0000000000000000",
            issuedAt: 1_000,
            expiresAt: nil
        )
        let payload = try encodedClaims(claims)
        let tamperedCredential = "\(base64URL(payload)).\(signature)"

        XCTAssertFalse(
            ActivationCredentialVerifier.verify(
                credential: tamperedCredential,
                expectedDeviceID: "0000000000000000",
                publicKeyData: key.publicKey.rawRepresentation,
                now: 2_000
            )
        )
    }

    private func makeCredential(
        key: Curve25519.Signing.PrivateKey,
        expiresAt: TimeInterval?
    ) throws -> String {
        let claims = ActivationClaims(
            version: 1,
            deviceID: deviceID,
            issuedAt: 1_000,
            expiresAt: expiresAt
        )
        let payload = try encodedClaims(claims)
        let signature = try key.signature(for: payload)
        return "\(base64URL(payload)).\(base64URL(signature))"
    }

    private func encodedClaims(_ claims: ActivationClaims) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(claims)
    }

    private func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
