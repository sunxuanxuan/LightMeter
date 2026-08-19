import { NextRequest, NextResponse } from "next/server";

import { siteConfig } from "@/lib/config";
import { cancelActiveOrder } from "@/lib/dal";
import { hasSameOrigin } from "@/lib/request-security";

export const dynamic = "force-dynamic";

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ orderNo: string }> },
) {
  if (!hasSameOrigin(request)) {
    return NextResponse.json({ errorCode: "INVALID_ORIGIN" }, { status: 403 });
  }
  const sessionToken = request.cookies.get(siteConfig.resultCookieName)?.value;
  if (!sessionToken) {
    return NextResponse.json(
      { errorCode: "ORDER_NOT_FOUND" },
      { status: 404 },
    );
  }

  const { orderNo } = await context.params;
  try {
    cancelActiveOrder(orderNo, sessionToken);
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (message === "ORDER_NOT_FOUND") {
      return NextResponse.json({ errorCode: message }, { status: 404 });
    }
    if (message === "ORDER_NOT_CANCELLABLE") {
      return NextResponse.json({ errorCode: message }, { status: 409 });
    }
    console.error("Unable to cancel activation order", {
      orderNo,
      cause: message || "unknown",
    });
    return NextResponse.json(
      { errorCode: "ORDER_CANCELLATION_FAILED" },
      { status: 500 },
    );
  }

  const response = NextResponse.json({ cancelled: true });
  response.cookies.set(siteConfig.resultCookieName, "", {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
  return response;
}
