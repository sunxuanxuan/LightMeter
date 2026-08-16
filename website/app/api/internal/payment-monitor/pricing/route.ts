import { updateProductPricing } from "@/lib/dal";
import { verifyMonitorRequest } from "@/lib/payments/monitor-auth";
import { monitorPricingUpdateSchema } from "@/lib/payments/monitor-pricing";
import { paymentMonitorSettings } from "@/lib/payments/monitor-settings";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_BODY_BYTES = 4 * 1024;
const MAX_REQUEST_CLOCK_SKEW_MS = 60 * 1_000;

function errorResponse(errorCode: string, status: number) {
  return Response.json(
    { errorCode },
    { status, headers: { "Cache-Control": "no-store" } },
  );
}

export async function POST(request: Request) {
  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (contentLength > MAX_BODY_BYTES) {
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

  const rawBody = await request.text();
  if (Buffer.byteLength(rawBody, "utf8") > MAX_BODY_BYTES) {
    return errorResponse("PAYLOAD_TOO_LARGE", 413);
  }
  const path = new URL(request.url).pathname;
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

  let json: unknown;
  try {
    json = JSON.parse(rawBody);
  } catch {
    return errorResponse("INVALID_JSON", 400);
  }
  const parsed = monitorPricingUpdateSchema.safeParse(json);
  if (!parsed.success) {
    return errorResponse("INVALID_PRICING", 400);
  }

  try {
    const pricing = updateProductPricing({
      ...parsed.data,
      monitorId,
      nonce,
      receivedAt: new Date(now),
    });
    return Response.json(
      { updated: true, pricing },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    const isDuplicate =
      error instanceof Error &&
      error.message.includes("UNIQUE constraint failed");
    if (isDuplicate) return errorResponse("DUPLICATE_COMMAND", 409);
    console.error("Monitor pricing update failed", {
      monitorId,
      cause: error instanceof Error ? error.message : "unknown",
    });
    return errorResponse("PRICING_UPDATE_FAILED", 500);
  }
}
