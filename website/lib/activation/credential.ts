import {
  createPrivateKey,
  createPublicKey,
  sign,
  verify,
  type KeyObject,
} from "node:crypto";

const ED25519_PKCS8_PREFIX = Buffer.from(
  "302e020100300506032b657004220420",
  "hex",
);

export type IssuedCredential = {
  credential: string;
  issuedAt: number;
  payload: string;
};

export function privateKeyFromSeed(seed: Buffer): KeyObject {
  if (seed.length !== 32) {
    throw new Error("Ed25519 private key seed must be 32 bytes");
  }
  return createPrivateKey({
    key: Buffer.concat([ED25519_PKCS8_PREFIX, seed]),
    format: "der",
    type: "pkcs8",
  });
}

export function issueCredential(
  deviceID: string,
  privateKey: KeyObject,
  issuedAt = Math.floor(Date.now() / 1_000),
): IssuedCredential {
  const payload = JSON.stringify({
    deviceID,
    issuedAt,
    version: 1,
  });
  const payloadData = Buffer.from(payload, "utf8");
  const signature = sign(null, payloadData, privateKey);
  return {
    credential: `${payloadData.toString("base64url")}.${signature.toString("base64url")}`,
    issuedAt,
    payload,
  };
}

export function verifyCredential(
  credential: string,
  expectedDeviceID: string,
  publicKey: KeyObject,
): boolean {
  const parts = credential.split(".");
  if (parts.length !== 2) return false;
  try {
    const payload = Buffer.from(parts[0], "base64url");
    const signature = Buffer.from(parts[1], "base64url");
    if (!verify(null, payload, publicKey, signature)) return false;
    const claims = JSON.parse(payload.toString("utf8")) as {
      deviceID?: string;
      issuedAt?: number;
      version?: number;
    };
    return (
      claims.deviceID === expectedDeviceID &&
      claims.version === 1 &&
      Number.isFinite(claims.issuedAt)
    );
  } catch {
    return false;
  }
}

export function publicKeyFor(privateKey: KeyObject): KeyObject {
  return createPublicKey(privateKey);
}
