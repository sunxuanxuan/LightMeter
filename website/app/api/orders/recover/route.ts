import { NextRequest, NextResponse } from "next/server";

import { siteConfig } from "@/lib/config";
import { recoverOrderSession } from "@/lib/dal";
import { hasSameOrigin } from "@/lib/request-security";
import { recoverOrderSchema } from "@/lib/validation";

export async function POST(request: NextRequest) {
  if (!hasSameOrigin(request)) {
    return NextResponse.json({ accepted: true });
  }
  const parsed = recoverOrderSchema.safeParse(await request.json());
  if (!parsed.success) {
    return NextResponse.json({ accepted: true });
  }
  if (process.env.NODE_ENV === "production") {
    return NextResponse.json({ accepted: true });
  }

  const recovered = recoverOrderSession(
    parsed.data.orderNo,
    parsed.data.email,
  );
  const response = NextResponse.json({
    accepted: true,
    localResultUrl: recovered
      ? `/activate/result/${recovered.orderNo}`
      : null,
  });
  if (recovered) {
    response.cookies.set(siteConfig.resultCookieName, recovered.sessionToken, {
      httpOnly: true,
      secure: false,
      sameSite: "lax",
      path: "/",
      maxAge: 60 * 60 * 24,
    });
  }
  return response;
}
