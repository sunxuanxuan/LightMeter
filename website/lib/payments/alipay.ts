import "server-only";

import { AlipaySdk } from "alipay-sdk";

import {
    type AlipayNotification,
    validateAlipayPayment,
} from "@/lib/payments/alipay-notification";

type AlipaySettings = {
  appId: string;
  sellerId: string;
  notifyUrl: string;
  sdk: AlipaySdk;
};

function normalizePem(value: string): string {
  return value.replaceAll("\\n", "\n");
}

function settings(): AlipaySettings {
  const appId = process.env.ALIPAY_APP_ID;
  const sellerId = process.env.ALIPAY_SELLER_ID;
  const privateKey = process.env.ALIPAY_PRIVATE_KEY;
  const publicKey = process.env.ALIPAY_PUBLIC_KEY;
  const notifyUrl =
    process.env.ALIPAY_NOTIFY_URL ??
    (process.env.APP_BASE_URL
      ? `${process.env.APP_BASE_URL.replace(/\/$/, "")}/api/payments/alipay/notify`
      : "");

  if (!appId || !sellerId || !privateKey || !publicKey || !notifyUrl) {
    throw new Error("ALIPAY_NOT_CONFIGURED");
  }
  if (process.env.NODE_ENV === "production" && !notifyUrl.startsWith("https://")) {
    throw new Error("ALIPAY_NOTIFY_URL_MUST_USE_HTTPS");
  }

  return {
    appId,
    sellerId,
    notifyUrl,
    sdk: new AlipaySdk({
      appId,
      privateKey: normalizePem(privateKey),
      alipayPublicKey: normalizePem(publicKey),
      gateway:
        process.env.ALIPAY_GATEWAY ??
        "https://openapi.alipay.com/gateway.do",
      signType: "RSA2",
      keyType: process.env.ALIPAY_KEY_TYPE === "PKCS1" ? "PKCS1" : "PKCS8",
      camelcase: true,
      timeout: 8_000,
    }),
  };
}

export async function createAlipayPayment(
  orderNo: string,
  amountMinor: number,
): Promise<string> {
  const config = settings();
  const result = (await config.sdk.exec("alipay.trade.precreate", {
    notify_url: config.notifyUrl,
    bizContent: {
      out_trade_no: orderNo,
      total_amount: (amountMinor / 100).toFixed(2),
      subject: "FilmLightMeter 单设备永久授权",
      timeout_express: "30m",
    },
  })) as {
    code?: string;
    msg?: string;
    subCode?: string;
    subMsg?: string;
    qrCode?: string;
  };

  if (result.code !== "10000" || !result.qrCode) {
    console.error("Alipay precreate failed", {
      code: result.code,
      msg: result.msg,
      subCode: result.subCode,
      subMsg: result.subMsg,
      orderNo,
    });
    throw new Error("ALIPAY_PRECREATE_FAILED");
  }
  return result.qrCode;
}

export function verifyAlipayNotification(notification: AlipayNotification) {
  const config = settings();
  if (!config.sdk.checkNotifySignV2(notification)) {
    throw new Error("ALIPAY_INVALID_SIGNATURE");
  }
  return validateAlipayPayment(notification, {
    appId: config.appId,
    sellerId: config.sellerId,
  });
}
