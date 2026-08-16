import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";

import { siteConfig } from "@/lib/config";
import {
    createOrder,
    getPaymentQrCode,
    savePaymentQrCode,
} from "@/lib/dal";
import { createAlipayPayment } from "@/lib/payments/alipay";
import { assertPersonalAlipayQrImage } from "@/lib/payments/personal-qr";
import { hasSameOrigin } from "@/lib/request-security";
import { createOrderSchema } from "@/lib/validation";

export async function POST(request: NextRequest) {
  if (!hasSameOrigin(request)) {
    return NextResponse.json({ errorCode: "INVALID_ORIGIN" }, { status: 403 });
  }
  const idempotencyKey = request.headers.get("Idempotency-Key");
  if (
    !idempotencyKey ||
    idempotencyKey.length < 8 ||
    idempotencyKey.length > 100
  ) {
    return NextResponse.json(
      { errorCode: "INVALID_IDEMPOTENCY_KEY" },
      { status: 400 },
    );
  }

  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (contentLength > 16 * 1024) {
    return NextResponse.json({ errorCode: "INVALID_ORDER" }, { status: 413 });
  }
  let input: unknown;
  try {
    input = await request.json();
  } catch {
    return NextResponse.json({ errorCode: "INVALID_ORDER" }, { status: 400 });
  }
  const parsed = createOrderSchema.safeParse(input);
  if (!parsed.success) {
    return NextResponse.json(
      {
        errorCode: "INVALID_ORDER",
        fieldErrors: z.flattenError(parsed.error).fieldErrors,
      },
      { status: 400 },
    );
  }

  let order: ReturnType<typeof createOrder> | null = null;
  try {
    if (siteConfig.paymentProvider === "personal_alipay_monitor") {
      await assertPersonalAlipayQrImage();
    }
    order = createOrder(parsed.data, idempotencyKey);
    if (
      siteConfig.paymentProvider === "alipay" &&
      !getPaymentQrCode(order.orderNo)
    ) {
      const qrCode = await createAlipayPayment(order.orderNo);
      savePaymentQrCode(order.orderNo, qrCode);
    }
  } catch (error) {
    const errorCode =
      error instanceof Error && error.message === "ALIPAY_NOT_CONFIGURED"
        ? "PAYMENT_NOT_CONFIGURED"
        : error instanceof Error &&
            error.message === "PAYMENT_AMOUNT_POOL_EXHAUSTED"
          ? "PAYMENT_CAPACITY_REACHED"
          : error instanceof Error &&
              (error.message === "ORDER_RATE_LIMITED" ||
                error.message === "BUYER_ACTIVE_ORDER_LIMIT_REACHED")
            ? "ORDER_RATE_LIMITED"
          : "PAYMENT_CREATION_FAILED";
    console.error("Unable to create Alipay payment", {
      errorCode,
      orderNo: order?.orderNo ?? null,
      cause: error instanceof Error ? error.message : "unknown",
    });
    return NextResponse.json({ errorCode }, { status: 503 });
  }
  if (!order) {
    return NextResponse.json(
      { errorCode: "PAYMENT_CREATION_FAILED" },
      { status: 503 },
    );
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
