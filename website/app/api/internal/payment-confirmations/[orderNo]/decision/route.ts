import { reviewPaymentConfirmation } from "@/lib/dal";
import { monitorConfirmationDecisionSchema } from "@/lib/payments/monitor-confirmation";
import { authenticateMonitorRequest } from "@/lib/payments/monitor-route-auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function POST(
  request: Request,
  context: { params: Promise<{ orderNo: string }> },
) {
  const authenticated = await authenticateMonitorRequest(request, 4 * 1024);
  if (authenticated instanceof Response) return authenticated;

  let json: unknown;
  try {
    json = JSON.parse(authenticated.rawBody);
  } catch {
    return Response.json({ errorCode: "INVALID_JSON" }, { status: 400 });
  }
  const parsed = monitorConfirmationDecisionSchema.safeParse(json);
  if (!parsed.success) {
    return Response.json({ errorCode: "INVALID_DECISION" }, { status: 400 });
  }

  const { orderNo } = await context.params;
  try {
    const status = reviewPaymentConfirmation({
      orderNo,
      ...parsed.data,
      monitorId: authenticated.monitorId,
      nonce: authenticated.nonce,
      reviewedAt: authenticated.receivedAt,
    });
    return Response.json(
      { accepted: true, status },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (message.includes("UNIQUE constraint failed")) {
      return Response.json({ errorCode: "DUPLICATE_COMMAND" }, { status: 409 });
    }
    if (message === "ORDER_NOT_FOUND") {
      return Response.json({ errorCode: message }, { status: 404 });
    }
    if (message === "ORDER_NOT_AWAITING_CONFIRMATION") {
      return Response.json({ errorCode: message }, { status: 409 });
    }
    console.error("Payment confirmation decision failed", {
      monitorId: authenticated.monitorId,
      orderNo,
      cause: message || "unknown",
    });
    return Response.json(
      { errorCode: "CONFIRMATION_DECISION_FAILED" },
      { status: 500 },
    );
  }
}
