import { afterEach, describe, expect, it } from "vitest";
import { NextRequest } from "next/server";

import { hasSameOrigin } from "@/lib/request-security";

const originalAppBaseUrl = process.env.APP_BASE_URL;

afterEach(() => {
  if (originalAppBaseUrl === undefined) {
    delete process.env.APP_BASE_URL;
  } else {
    process.env.APP_BASE_URL = originalAppBaseUrl;
  }
});

function request(headers: Record<string, string>): NextRequest {
  return new NextRequest("http://127.0.0.1:3000/api/orders", { headers });
}

describe("hasSameOrigin", () => {
  it("accepts a direct same-origin request", () => {
    delete process.env.APP_BASE_URL;

    expect(
      hasSameOrigin(
        request({
          origin: "http://localhost:3000",
          host: "localhost:3000",
        }),
      ),
    ).toBe(true);
  });

  it("uses APP_BASE_URL when the proxy rewrites Host", () => {
    process.env.APP_BASE_URL = "https://lightmeter.example.cn";

    expect(
      hasSameOrigin(
        request({
          origin: "https://lightmeter.example.cn",
          host: "127.0.0.1:3000",
        }),
      ),
    ).toBe(true);
  });

  it("accepts trusted forwarded authority when APP_BASE_URL is absent", () => {
    delete process.env.APP_BASE_URL;

    expect(
      hasSameOrigin(
        request({
          origin: "https://lightmeter.example.cn",
          host: "127.0.0.1:3000",
          "x-forwarded-host": "lightmeter.example.cn",
          "x-forwarded-proto": "https",
        }),
      ),
    ).toBe(true);
  });

  it("rejects an unrelated origin", () => {
    process.env.APP_BASE_URL = "https://lightmeter.example.cn";

    expect(
      hasSameOrigin(
        request({
          origin: "https://attacker.example.net",
          host: "127.0.0.1:3000",
          "x-forwarded-host": "lightmeter.example.cn",
          "x-forwarded-proto": "https",
        }),
      ),
    ).toBe(false);
  });

  it("fails closed when APP_BASE_URL is still a placeholder", () => {
    process.env.APP_BASE_URL = "https://your-domain.example";

    expect(
      hasSameOrigin(
        request({
          origin: "https://lightmeter.example.cn",
          host: "lightmeter.example.cn",
          "x-forwarded-host": "lightmeter.example.cn",
          "x-forwarded-proto": "https",
        }),
      ),
    ).toBe(false);
  });
});
