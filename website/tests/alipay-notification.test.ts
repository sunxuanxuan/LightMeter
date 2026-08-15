import { describe, expect, it } from "vitest";

import {
  amountToMinor,
  validateAlipayPayment,
} from "@/lib/payments/alipay-notification";

const expected = {
  appId: "2026000000000000",
  sellerId: "2088000000000000",
  amountMinor: 990,
};

function successfulNotification(overrides: Record<string, string> = {}) {
  return {
    app_id: expected.appId,
    seller_id: expected.sellerId,
    out_trade_no: "FLM-20260815-ABCDEF12",
    trade_no: "2026081522000000000001",
    trade_status: "TRADE_SUCCESS",
    total_amount: "9.90",
    ...overrides,
  };
}

describe("Alipay payment notification", () => {
  it("converts decimal amounts without floating point rounding", () => {
    expect(amountToMinor("9.90")).toBe(990);
    expect(amountToMinor("9.9")).toBe(990);
    expect(amountToMinor("9.900")).toBeNull();
    expect(amountToMinor("-9.90")).toBeNull();
  });

  it("accepts a successful payment for the configured merchant", () => {
    expect(
      validateAlipayPayment(successfulNotification(), expected),
    ).toEqual({
      orderNo: "FLM-20260815-ABCDEF12",
      tradeNo: "2026081522000000000001",
      amountMinor: 990,
    });
  });

  it.each([
    ["wrong amount", { total_amount: "0.01" }, "ALIPAY_AMOUNT_MISMATCH"],
    ["wrong app", { app_id: "other" }, "ALIPAY_APP_ID_MISMATCH"],
    ["wrong seller", { seller_id: "other" }, "ALIPAY_SELLER_ID_MISMATCH"],
    ["pending trade", { trade_status: "WAIT_BUYER_PAY" }, "ALIPAY_TRADE_NOT_SUCCESSFUL"],
  ])("rejects %s", (_name, overrides, error) => {
    expect(() =>
      validateAlipayPayment(successfulNotification(overrides), expected),
    ).toThrow(error);
  });
});
