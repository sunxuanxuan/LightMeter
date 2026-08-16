import "server-only";

export type PaymentMonitorSettings = {
  monitorId: string;
  secret: string;
};

export function paymentMonitorSettings(): PaymentMonitorSettings {
  const monitorId = process.env.PAYMENT_MONITOR_ID;
  const secret = process.env.PAYMENT_MONITOR_SECRET;

  if (monitorId && secret && secret.length >= 32) {
    return { monitorId, secret };
  }
  if (process.env.NODE_ENV === "production") {
    throw new Error("PAYMENT_MONITOR_NOT_CONFIGURED");
  }
  return {
    monitorId: "local-debug-monitor",
    secret: "local-debug-monitor-secret-change-me",
  };
}
