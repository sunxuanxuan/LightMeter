import "server-only";

import { verifyMonitorRequest } from "@/lib/payments/monitor-auth";
import { consumeMonitorRequestNonce } from "@/lib/payments/monitor-nonce";
import { paymentMonitorSettings } from "@/lib/payments/monitor-settings";

const MAX_REQUEST_CLOCK_SKEW_MS = 60 * 1_000;

export type AuthenticatedMonitorRequest = {
  monitorId: string;
  nonce: string;
  rawBody: string;
  receivedAt: Date;
};

function errorResponse(errorCode: string, status: number) {
  return Response.json(
    { errorCode },
    { status, headers: { "Cache-Control": "no-store" } },
  );
}

export async function authenticateMonitorRequest(
  request: Request,
  maxBodyBytes: number,
): Promise<AuthenticatedMonitorRequest | Response> {
  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (contentLength > maxBodyBytes) {
    return errorResponse("PAYLOAD_TOO_LARGE", 413);
  }

  let settings: ReturnType<typeof paymentMonitorSettings>;
  try {
    settings = paymentMonitorSettings();
  } catch {
    return errorResponse("PAYMENT_MONITOR_NOT_CONFIGURED", 503);
  }

  const monitorId = request.headers.get("x-monitor-id") ?? "";
  const timestamp = request.headers.get("x-timestamp") ?? "";
  const nonce = request.headers.get("x-nonce") ?? "";
  const signature = request.headers.get("x-signature") ?? "";
  const timestampNumber = Number(timestamp);
  const now = Date.now();

  if (monitorId !== settings.monitorId) {
    return errorResponse("INVALID_MONITOR", 401);
  }
  if (
    !Number.isSafeInteger(timestampNumber) ||
    Math.abs(now - timestampNumber) > MAX_REQUEST_CLOCK_SKEW_MS
  ) {
    return errorResponse("INVALID_TIMESTAMP", 401);
  }
  if (!/^[A-Za-z0-9_-]{16,128}$/.test(nonce)) {
    return errorResponse("INVALID_NONCE", 401);
  }

  const rawBody = request.method === "GET" ? "" : await request.text();
  const path = new URL(request.url).pathname;
  if (Buffer.byteLength(rawBody, "utf8") > maxBodyBytes) {
    return errorResponse("PAYLOAD_TOO_LARGE", 413);
  }
  if (
    !verifyMonitorRequest(
      {
        method: request.method,
        path,
        timestamp,
        nonce,
        rawBody,
      },
      signature,
      settings.secret,
    )
  ) {
    return errorResponse("INVALID_SIGNATURE", 401);
  }

  const receivedAt = new Date(now);
  try {
    if (
      !consumeMonitorRequestNonce({
        monitorId,
        nonce,
        method: request.method,
        path,
        usedAt: receivedAt,
      })
    ) {
      return errorResponse("REPLAYED_NONCE", 409);
    }
  } catch (error) {
    console.error("Unable to persist payment monitor nonce", {
      monitorId,
      cause: error instanceof Error ? error.message : "unknown",
    });
    return errorResponse("NONCE_STORAGE_FAILED", 503);
  }

  return {
    monitorId,
    nonce,
    rawBody,
    receivedAt,
  };
}
