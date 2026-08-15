export const siteConfig = {
  name: "FilmLightMeter",
  description: "面向胶片摄影的离线测光工具",
  priceMinor: Number(process.env.PRODUCT_PRICE_MINOR ?? 990),
  currency: process.env.PRODUCT_CURRENCY ?? "CNY",
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
