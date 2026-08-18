import { listPendingPaymentConfirmations } from "@/lib/dal";
import { authenticateMonitorRequest } from "@/lib/payments/monitor-route-auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const authenticated = await authenticateMonitorRequest(request, 0);
  if (authenticated instanceof Response) return authenticated;

  try {
    return Response.json(
      {
        confirmations: listPendingPaymentConfirmations(
          authenticated.receivedAt,
        ),
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    console.error("Pending payment confirmation query failed", {
      monitorId: authenticated.monitorId,
      cause: error instanceof Error ? error.message : "unknown",
    });
    return Response.json(
      { errorCode: "CONFIRMATION_QUERY_FAILED" },
      { status: 500, headers: { "Cache-Control": "no-store" } },
    );
  }
}
