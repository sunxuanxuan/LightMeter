#!/usr/bin/env swift

import CryptoKit
import Foundation

struct Claims: Codable {
    let version: Int
    let deviceID: String
    let issuedAt: TimeInterval
    let expiresAt: TimeInterval?
}

func base64URL(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data("错误：\(message)\n".utf8))
    exit(1)
}

let arguments = CommandLine.arguments
guard arguments.count >= 3 else {
    fail("""
    用法：
      activation_signer.swift generate-key <private-key-path>
      activation_signer.swift public-key <private-key-path>
      activation_signer.swift sign <private-key-path> <device-id> [有效天数]
    """)
}

switch arguments[1] {
case "generate-key":
    let key = Curve25519.Signing.PrivateKey()
    let url = URL(fileURLWithPath: arguments[2])
    try key.rawRepresentation.write(to: url, options: .atomic)
    try FileManager.default.setAttributes(
        [.posixPermissions: 0o600],
        ofItemAtPath: url.path
    )
    print("私钥已写入：\(url.path)")
    print("App 公钥：\(key.publicKey.rawRepresentation.base64EncodedString())")

case "public-key":
    let keyData = try Data(contentsOf: URL(fileURLWithPath: arguments[2]))
    let key = try Curve25519.Signing.PrivateKey(rawRepresentation: keyData)
    print(key.publicKey.rawRepresentation.base64EncodedString())

case "sign":
    guard arguments.count >= 4 else { fail("缺少设备 ID") }
    let deviceID = arguments[3]
        .replacingOccurrences(of: " ", with: "")
        .replacingOccurrences(of: "-", with: "")
        .uppercased()
    guard deviceID.count == 16,
          deviceID.allSatisfy({ $0.isHexDigit }) else {
        fail("设备 ID 必须是 16 位十六进制字符")
    }
    let keyData = try Data(contentsOf: URL(fileURLWithPath: arguments[2]))
    let key = try Curve25519.Signing.PrivateKey(rawRepresentation: keyData)
    let validDays = arguments.count >= 5 ? Double(arguments[4]) : nil
    if arguments.count >= 5,
       validDays == nil || !validDays!.isFinite || validDays! <= 0 {
        fail("有效天数必须是正数")
    }
    let now = Date().timeIntervalSince1970
    let claims = Claims(
        version: 1,
        deviceID: deviceID,
        issuedAt: now,
        expiresAt: validDays.map { now + $0 * 86_400 }
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    let payload = try encoder.encode(claims)
    let signature = try key.signature(for: payload)
    print("\(base64URL(payload)).\(base64URL(signature))")

default:
    fail("未知命令：\(arguments[1])")
}
