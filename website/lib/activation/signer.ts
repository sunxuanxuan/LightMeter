import "server-only";

import { createHash } from "node:crypto";

import { siteConfig } from "@/lib/config";
import {
  issueCredential,
  privateKeyFromSeed,
  publicKeyFor,
} from "@/lib/activation/credential";

function signingSeed(): Buffer {
  const configured = process.env.ACTIVATION_PRIVATE_KEY_BASE64;
  if (configured) {
    const seed = Buffer.from(configured, "base64");
    if (seed.length !== 32) {
      throw new Error("ACTIVATION_PRIVATE_KEY_BASE64 must decode to 32 bytes");
    }
    return seed;
  }
  if (process.env.NODE_ENV === "production") {
    throw new Error("ACTIVATION_PRIVATE_KEY_BASE64 is required in production");
  }
  return createHash("sha256")
    .update("FilmLightMeter isolated local preview signing key")
    .digest();
}

export function signDeviceCredential(deviceID: string) {
  if (!siteConfig.signingKeyId) {
    throw new Error("ACTIVATION_SIGNING_KEY_ID is required");
  }
  const privateKey = privateKeyFromSeed(signingSeed());
  return {
    ...issueCredential(deviceID, privateKey),
    signingKeyId: siteConfig.signingKeyId,
    isDevelopmentCredential: !process.env.ACTIVATION_PRIVATE_KEY_BASE64,
  };
}

export function localSigningPublicKey(): string {
  const privateKey = privateKeyFromSeed(signingSeed());
  const spki = publicKeyFor(privateKey).export({
    format: "der",
    type: "spki",
  });
  return Buffer.from(spki).subarray(-32).toString("base64");
}
