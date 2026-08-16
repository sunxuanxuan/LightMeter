export type AlipayNotification = Record<string, string>;

export type ValidatedAlipayPayment = {
  orderNo: string;
  tradeNo: string;
  amountMinor: number;
};

export function amountToMinor(value: string): number | null {
  const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(value);
  if (!match) return null;
  const yuan = Number(match[1]);
  const cents = (match[2] ?? "").padEnd(2, "0");
  return yuan * 100 + Number(cents);
}

export function validateAlipayPayment(
  notification: AlipayNotification,
  expected: {
    appId: string;
    sellerId: string;
    amountMinor?: number;
  },
): ValidatedAlipayPayment {
  if (
    notification.trade_status !== "TRADE_SUCCESS" &&
    notification.trade_status !== "TRADE_FINISHED"
  ) {
    throw new Error("ALIPAY_TRADE_NOT_SUCCESSFUL");
  }
  if (notification.app_id !== expected.appId) {
    throw new Error("ALIPAY_APP_ID_MISMATCH");
  }
  if (notification.seller_id !== expected.sellerId) {
    throw new Error("ALIPAY_SELLER_ID_MISMATCH");
  }

  const amountMinor = amountToMinor(notification.total_amount ?? "");
  if (
    amountMinor === null ||
    (expected.amountMinor !== undefined &&
      amountMinor !== expected.amountMinor)
  ) {
    throw new Error("ALIPAY_AMOUNT_MISMATCH");
  }
  if (!notification.out_trade_no || !notification.trade_no) {
    throw new Error("ALIPAY_PAYMENT_REFERENCE_MISSING");
  }

  return {
    orderNo: notification.out_trade_no,
    tradeNo: notification.trade_no,
    amountMinor,
  };
}
