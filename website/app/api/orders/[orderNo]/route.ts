import { NextRequest } from "next/server";

import { siteConfig } from "@/lib/config";
import { getOrder } from "@/lib/dal";

export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ orderNo: string }> },
) {
  const { orderNo } = await context.params;
  const sessionToken = request.cookies.get(siteConfig.resultCookieName)?.value;
  const order = sessionToken ? getOrder(orderNo, sessionToken) : null;
  if (!order) {
    return Response.json({ errorCode: "ORDER_NOT_FOUND" }, { status: 404 });
  }
  return Response.json(
    { status: order.status, expiresAt: order.expiresAt },
    { headers: { "Cache-Control": "private, no-store" } },
  );
}
