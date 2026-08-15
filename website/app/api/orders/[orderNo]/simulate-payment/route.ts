import { NextRequest, NextResponse } from "next/server";

import { siteConfig } from "@/lib/config";
import { simulatePayment } from "@/lib/dal";
import { hasSameOrigin } from "@/lib/request-security";

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ orderNo: string }> },
) {
  if (process.env.NODE_ENV === "production") {
    return NextResponse.json({ errorCode: "NOT_FOUND" }, { status: 404 });
  }
  if (!hasSameOrigin(request)) {
    return NextResponse.json({ errorCode: "INVALID_ORIGIN" }, { status: 403 });
  }
  const { orderNo } = await context.params;
  const sessionToken = request.cookies.get(siteConfig.resultCookieName)?.value;
  if (!sessionToken) {
    return NextResponse.json({ errorCode: "UNAUTHORIZED" }, { status: 401 });
  }
  try {
    const order = simulatePayment(orderNo, sessionToken);
    return NextResponse.json({ status: order.status });
  } catch (error) {
    const errorCode =
      error instanceof Error ? error.message : "PAYMENT_SIMULATION_FAILED";
    return NextResponse.json({ errorCode }, { status: 400 });
  }
}
