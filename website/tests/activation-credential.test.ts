import { createHash } from "node:crypto";

import { describe, expect, it } from "vitest";

import {
  issueCredential,
  privateKeyFromSeed,
  publicKeyFor,
  verifyCredential,
} from "@/lib/activation/credential";
import {
  createOrderSchema,
  formatDeviceId,
  normalizeDeviceId,
} from "@/lib/validation";

describe("activation credential protocol", () => {
  const privateKey = privateKeyFromSeed(
    createHash("sha256").update("test-key").digest(),
  );
  const publicKey = publicKeyFor(privateKey);

  it("signs a v1 credential bound to one device", () => {
    const issued = issueCredential(
      "A1B2C3D4E5F6A7B8",
      privateKey,
      1_786_723_200,
    );

    expect(
      verifyCredential(issued.credential, "A1B2C3D4E5F6A7B8", publicKey),
    ).toBe(true);
    expect(
      verifyCredential(issued.credential, "0000000000000000", publicKey),
    ).toBe(false);
  });

  it("rejects a modified credential", () => {
    const issued = issueCredential("A1B2C3D4E5F6A7B8", privateKey);
    const [payload, signature] = issued.credential.split(".");
    const modified = `${payload.slice(0, -1)}A.${signature}`;

    expect(
      verifyCredential(modified, "A1B2C3D4E5F6A7B8", publicKey),
    ).toBe(false);
  });
});

describe("order validation", () => {
  it("normalizes a grouped device ID", () => {
    expect(normalizeDeviceId("a1b2 c3d4-e5f6 a7b8")).toBe(
      "A1B2C3D4E5F6A7B8",
    );
    expect(formatDeviceId("A1B2C3D4E5F6A7B8")).toBe(
      "A1B2 C3D4 E5F6 A7B8",
    );
  });

  it("requires valid order data and accepted terms", () => {
    const result = createOrderSchema.safeParse({
      platform: "android",
      deviceID: "A1B2 C3D4 E5F6 A7B8",
      email: " USER@example.com ",
      acceptedTerms: true,
    });

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.deviceID).toBe("A1B2C3D4E5F6A7B8");
      expect(result.data.email).toBe("user@example.com");
    }
  });
});
