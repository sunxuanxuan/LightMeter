import { NextRequest } from "next/server";

import { siteConfig } from "@/lib/config";
import { claimPersonalPayment } from "@/lib/dal";
import { hasSameOrigin } from "@/lib/request-security";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ orderNo: string }> },
) {
  if (!hasSameOrigin(request)) {
    return Response.json({ errorCode: "INVALID_ORIGIN" }, { status: 403 });
  }
  const { orderNo } = await context.params;
  const sessionToken = request.cookies.get(siteConfig.resultCookieName)?.value;
  if (!sessionToken) {
    return Response.json({ errorCode: "ORDER_NOT_FOUND" }, { status: 404 });
  }

  try {
    const result = claimPersonalPayment(orderNo, sessionToken);
    return Response.json(result, {
      headers: { "Cache-Control": "private, no-store" },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (message === "ORDER_NOT_FOUND") {
      return Response.json({ errorCode: message }, { status: 404 });
    }
    if (message === "PAYMENT_CLAIM_LIMIT_REACHED") {
      return Response.json({ errorCode: message }, { status: 429 });
    }
    return Response.json(
      { errorCode: "ORDER_NOT_CLAIMABLE" },
      { status: 409 },
    );
  }
}
