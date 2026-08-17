import type { NextRequest } from "next/server";

function firstForwardedValue(value: string | null): string | null {
  return value?.split(",", 1)[0]?.trim() || null;
}

function configuredPublicOrigin(): string | false | null {
  const configured = process.env.APP_BASE_URL;
  if (!configured) return null;
  try {
    const url = new URL(configured);
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.hostname.endsWith(".example")
    ) {
      return false;
    }
    return url.origin;
  } catch {
    return false;
  }
}

export function hasSameOrigin(request: NextRequest): boolean {
  const originHeader = request.headers.get("origin");
  if (!originHeader) return process.env.NODE_ENV !== "production";

  let requestOrigin: URL;
  try {
    requestOrigin = new URL(originHeader);
  } catch {
    return false;
  }
  if (!["http:", "https:"].includes(requestOrigin.protocol)) return false;

  const publicOrigin = configuredPublicOrigin();
  if (publicOrigin !== null) {
    return publicOrigin !== false && requestOrigin.origin === publicOrigin;
  }

  const forwardedHost = firstForwardedValue(
    request.headers.get("x-forwarded-host"),
  );
  const forwardedProtocol = firstForwardedValue(
    request.headers.get("x-forwarded-proto"),
  );
  if (
    forwardedHost &&
    forwardedProtocol &&
    requestOrigin.host === forwardedHost &&
    requestOrigin.protocol === `${forwardedProtocol.toLowerCase()}:`
  ) {
    return true;
  }

  const host = request.headers.get("host");
  return Boolean(host && requestOrigin.host === host);
}
