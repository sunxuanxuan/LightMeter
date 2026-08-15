import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";

import { siteConfig } from "@/lib/config";
import { createOrder } from "@/lib/dal";
import { hasSameOrigin } from "@/lib/request-security";
import { createOrderSchema } from "@/lib/validation";

export async function POST(request: NextRequest) {
  if (!hasSameOrigin(request)) {
    return NextResponse.json({ errorCode: "INVALID_ORIGIN" }, { status: 403 });
  }
  const idempotencyKey = request.headers.get("Idempotency-Key");
  if (!idempotencyKey || idempotencyKey.length < 8 || idempotencyKey.length > 100) {
    return NextResponse.json(
      { errorCode: "INVALID_IDEMPOTENCY_KEY" },
      { status: 400 },
    );
  }

  const parsed = createOrderSchema.safeParse(await request.json());
  if (!parsed.success) {
    return NextResponse.json(
      {
        errorCode: "INVALID_ORDER",
        fieldErrors: z.flattenError(parsed.error).fieldErrors,
      },
      { status: 400 },
    );
  }

  const order = createOrder(parsed.data, idempotencyKey);
  const response = NextResponse.json({
    orderNo: order.orderNo,
    resultUrl: `/activate/result/${order.orderNo}`,
  });
  response.cookies.set(siteConfig.resultCookieName, order.sessionToken, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 60 * 60 * 24 * 7,
  });
  return response;
}
