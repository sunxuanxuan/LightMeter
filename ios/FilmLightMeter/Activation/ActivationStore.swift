import Combine
import CryptoKit
import Foundation
import Security

@MainActor
final class ActivationStore: ObservableObject {
    @Published private(set) var isActivated = false
    @Published private(set) var deviceID = ""
    @Published var errorMessage: String?

    private let keychain = KeychainStore()

    init() {
        let stored = keychain.string(for: "device_id")
        let identifier = stored ?? Self.makeDeviceID()
        if stored == nil {
            keychain.set(identifier, for: "device_id")
        }
        deviceID = identifier
#if DEBUG
        isActivated = true
#else
        if let credential = keychain.string(for: "activation_credential") {
            isActivated = Self.verify(credential: credential, deviceID: identifier)
        }
#endif
    }

    func activate(credential: String) {
        let normalized = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.verify(credential: normalized, deviceID: deviceID) else {
            errorMessage = "激活凭证无效或不属于此设备"
            return
        }
        keychain.set(normalized, for: "activation_credential")
        errorMessage = nil
        isActivated = true
    }

    private static func verify(credential: String, deviceID: String) -> Bool {
        guard let publicKeyData = Data(base64Encoded: releasePublicKeyBase64) else {
            return false
        }
        return ActivationCredentialVerifier.verify(
            credential: credential,
            expectedDeviceID: deviceID,
            publicKeyData: publicKeyData
        )
    }

    private static func makeDeviceID() -> String {
        var bytes = [UInt8](repeating: 0, count: 8)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        precondition(status == errSecSuccess)
        return bytes.map { String(format: "%02X", $0) }.joined()
    }

    // The matching private key is stored outside version control in ios/.secrets.
    private static let releasePublicKeyBase64 =
        "81C0KEAE1R38JV0E5LI5x3tuZgzwUK8FGQv4w62dcBQ="
}

struct ActivationClaims: Codable {
    let version: Int
    let deviceID: String
    let issuedAt: TimeInterval
    let expiresAt: TimeInterval?
}

enum ActivationCredentialVerifier {
    private static let credentialVersion = 1
    private static let maximumCredentialLength = 2_048
    private static let maximumPayloadLength = 1_024

    static func verify(
        credential: String,
        expectedDeviceID: String,
        publicKeyData: Data,
        now: TimeInterval = Date().timeIntervalSince1970
    ) -> Bool {
        guard !credential.isEmpty,
              credential.utf8.count <= maximumCredentialLength,
              expectedDeviceID.count == 16,
              expectedDeviceID.allSatisfy(\.isHexDigit),
              publicKeyData.count == 32 else {
            return false
        }

        let parts = credential.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2,
              let payload = Data(base64URLEncoded: String(parts[0])),
              !payload.isEmpty,
              payload.count <= maximumPayloadLength,
              let signature = Data(base64URLEncoded: String(parts[1])),
              signature.count == 64,
              let publicKey = try? Curve25519.Signing.PublicKey(
                rawRepresentation: publicKeyData
              ),
              publicKey.isValidSignature(signature, for: payload),
              let claims = try? JSONDecoder().decode(ActivationClaims.self, from: payload),
              claims.version == credentialVersion,
              claims.deviceID == expectedDeviceID,
              claims.issuedAt.isFinite,
              claims.issuedAt >= 0 else {
            return false
        }

        guard let expiresAt = claims.expiresAt else {
            return true
        }
        return expiresAt.isFinite && expiresAt >= now
    }
}

private struct KeychainStore {
    private let service = "com.lightmeter.app.ios.activation"

    func string(for account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    func set(_ value: String, for account: String) {
        let data = Data(value.utf8)
        let query = baseQuery(account: account)
        let attributes = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            var insert = query
            insert[kSecValueData as String] = data
            insert[kSecAttrAccessible as String] =
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

private extension Data {
    init?(base64URLEncoded value: String) {
        var normalized = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        normalized += String(repeating: "=", count: (4 - normalized.count % 4) % 4)
        self.init(base64Encoded: normalized)
    }
}
