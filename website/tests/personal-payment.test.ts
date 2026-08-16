import { describe, expect, it } from "vitest";

import {
  signMonitorRequest,
  verifyMonitorRequest,
} from "@/lib/payments/monitor-auth";
import { monitorPaymentEventSchema } from "@/lib/payments/monitor-event";
import {
  availablePaymentAmounts,
  selectPaymentAmount,
} from "@/lib/payments/personal-payment";

describe("personal payment amount pool", () => {
  it("offers every cent from the maximum discount through list price", () => {
    expect(
      availablePaymentAmounts(990, 3, new Set()),
    ).toEqual([987, 988, 989, 990]);
  });

  it("excludes amounts reserved by active orders", () => {
    expect(
      availablePaymentAmounts(990, 3, new Set([988, 990])),
    ).toEqual([987, 989]);
  });

  it("rejects an exhausted amount pool", () => {
    expect(() => selectPaymentAmount(990, 1, new Set([989, 990]))).toThrow(
      "PAYMENT_AMOUNT_POOL_EXHAUSTED",
    );
  });
});

describe("payment monitor request authentication", () => {
  const request = {
    method: "POST",
    path: "/api/internal/payment-monitor/events",
    timestamp: "1786812345000",
    nonce: "nonce_1234567890",
    rawBody: '{"amountMinor":987}',
  };
  const secret = "test-secret-with-at-least-32-characters";

  it("accepts the exact signed request", () => {
    const signature = signMonitorRequest(request, secret);
    expect(verifyMonitorRequest(request, signature, secret)).toBe(true);
  });

  it("rejects a modified body", () => {
    const signature = signMonitorRequest(request, secret);
    expect(
      verifyMonitorRequest(
        { ...request, rawBody: '{"amountMinor":988}' },
        signature,
        secret,
      ),
    ).toBe(false);
  });
});

describe("payment monitor event validation", () => {
  it("accepts an integer-minor-unit Alipay event", () => {
    const result = monitorPaymentEventSchema.safeParse({
      eventId: "event_1234567890123456",
      channel: "alipay",
      amountMinor: 987,
      observedAt: 1_786_812_345_000,
      notificationHash:
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      monitorVersion: "0.1.0-debug",
    });
    expect(result.success).toBe(true);
  });

  it("rejects floating-point amounts", () => {
    const result = monitorPaymentEventSchema.safeParse({
      eventId: "event_1234567890123456",
      channel: "alipay",
      amountMinor: 987.5,
      observedAt: 1_786_812_345_000,
      notificationHash:
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      monitorVersion: "0.1.0-debug",
    });
    expect(result.success).toBe(false);
  });
});
