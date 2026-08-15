import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";

import { siteConfig } from "@/lib/config";
import {
  createOrder,
  getPaymentQrCode,
  savePaymentQrCode,
} from "@/lib/dal";
import { createAlipayPayment } from "@/lib/payments/alipay";
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
  try {
    if (!getPaymentQrCode(order.orderNo)) {
      const qrCode = await createAlipayPayment(order.orderNo);
      savePaymentQrCode(order.orderNo, qrCode);
    }
  } catch (error) {
    const errorCode =
      error instanceof Error && error.message === "ALIPAY_NOT_CONFIGURED"
        ? "PAYMENT_NOT_CONFIGURED"
        : "PAYMENT_CREATION_FAILED";
    console.error("Unable to create Alipay payment", {
      errorCode,
      orderNo: order.orderNo,
      cause: error instanceof Error ? error.message : "unknown",
    });
    return NextResponse.json({ errorCode }, { status: 503 });
  }

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
