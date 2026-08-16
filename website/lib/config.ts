export type PaymentProvider = "alipay" | "personal_alipay_monitor";

function paymentProvider(): PaymentProvider {
  return process.env.PAYMENT_PROVIDER === "alipay"
    ? "alipay"
    : "personal_alipay_monitor";
}

export const siteConfig = {
  name: "FilmLightMeter",
  description: "面向胶片摄影的离线测光工具",
  priceMinor: Number(process.env.PRODUCT_PRICE_MINOR ?? 990),
  currency: process.env.PRODUCT_CURRENCY ?? "CNY",
  paymentProvider: paymentProvider(),
  personalPaymentDiscountMaxMinor: Number(
    process.env.PERSONAL_PAYMENT_DISCOUNT_MAX_MINOR ?? 100,
  ),
  personalPaymentLifetimeSeconds: Number(
    process.env.PERSONAL_PAYMENT_LIFETIME_SECONDS ?? 15 * 60,
  ),
  personalPaymentGraceSeconds: Number(
    process.env.PERSONAL_PAYMENT_GRACE_SECONDS ?? 2 * 60,
  ),
  personalPaymentMaxOrdersPerMinute: Number(
    process.env.PERSONAL_PAYMENT_MAX_ORDERS_PER_MINUTE ?? 5,
  ),
  personalPaymentMaxActiveOrdersPerBuyer: Number(
    process.env.PERSONAL_PAYMENT_MAX_ACTIVE_ORDERS_PER_BUYER ?? 2,
  ),
  personalPaymentQrImagePath:
    process.env.PERSONAL_ALIPAY_QR_IMAGE_PATH ??
    "./asset/20260816-161025.jpeg",
  resultCookieName: "flm_result_session",
  signingKeyId:
    process.env.ACTIVATION_SIGNING_KEY_ID ??
    (process.env.NODE_ENV === "production" ? "" : "local-preview-v1"),
} as const;

export function formatPrice(amountMinor: number, currency: string): string {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
  }).format(amountMinor / 100);
}
