import { NextRequest } from "next/server";
import QRCode from "qrcode";

import { siteConfig } from "@/lib/config";
import { getOrder } from "@/lib/dal";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ orderNo: string }> },
) {
  const { orderNo } = await context.params;
  const sessionToken = request.cookies.get(siteConfig.resultCookieName)?.value;
  const order = sessionToken ? getOrder(orderNo, sessionToken) : null;
  if (!order?.paymentQrCode || order.status !== "pending") {
    return Response.json({ errorCode: "PAYMENT_QR_NOT_FOUND" }, { status: 404 });
  }

  const png = await QRCode.toBuffer(order.paymentQrCode, {
    type: "png",
    width: 320,
    margin: 2,
    errorCorrectionLevel: "M",
  });
  return new Response(new Uint8Array(png), {
    headers: {
      "Content-Type": "image/png",
      "Cache-Control": "private, no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}
