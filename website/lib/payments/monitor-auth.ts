import { createHash, createHmac, timingSafeEqual } from "node:crypto";

export type MonitorSignatureInput = {
  method: string;
  path: string;
  timestamp: string;
  nonce: string;
  rawBody: string;
};

export function monitorSignaturePayload(input: MonitorSignatureInput): string {
  const bodyHash = createHash("sha256")
    .update(input.rawBody, "utf8")
    .digest("hex");
  return [
    input.method.toUpperCase(),
    input.path,
    input.timestamp,
    input.nonce,
    bodyHash,
  ].join("\n");
}

export function signMonitorRequest(
  input: MonitorSignatureInput,
  secret: string,
): string {
  return createHmac("sha256", secret)
    .update(monitorSignaturePayload(input))
    .digest("hex");
}

export function verifyMonitorRequest(
  input: MonitorSignatureInput,
  signature: string,
  secret: string,
): boolean {
  if (!/^[0-9a-f]{64}$/i.test(signature)) return false;
  const expected = Buffer.from(signMonitorRequest(input, secret), "hex");
  const actual = Buffer.from(signature, "hex");
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}
