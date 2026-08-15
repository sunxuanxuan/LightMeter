import { fulfillAlipayPayment } from "@/lib/dal";
import {
  verifyAlipayNotification,
} from "@/lib/payments/alipay";
import type { AlipayNotification } from "@/lib/payments/alipay-notification";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

function textResponse(body: "success" | "failure", status = 200) {
  return new Response(body, {
    status,
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "no-store",
    },
  });
}

export async function POST(request: Request) {
  const contentLength = Number(request.headers.get("content-length") ?? 0);
  if (contentLength > 64 * 1024) {
    return textResponse("failure", 413);
  }

  try {
    const formData = await request.formData();
    const notification: AlipayNotification = {};
    for (const [key, value] of formData.entries()) {
      if (typeof value !== "string") {
        return textResponse("failure", 400);
      }
      notification[key] = value;
    }

    const payment = verifyAlipayNotification(notification);
    fulfillAlipayPayment(payment);
    return textResponse("success");
  } catch (error) {
    console.error("Alipay notification rejected", {
      cause: error instanceof Error ? error.message : "unknown",
    });
    return textResponse("failure", 400);
  }
}
