import "server-only";

import { randomBytes, randomUUID } from "node:crypto";

import { signDeviceCredential } from "@/lib/activation/signer";
import { siteConfig, type PaymentProvider } from "@/lib/config";
import {
    createSessionToken,
    decrypt,
    encrypt,
    hashesMatch,
    lookupHash,
    tokenHash,
} from "@/lib/crypto";
import { db } from "@/lib/db";
import type { MonitorPaymentEvent } from "@/lib/payments/monitor-event";
import { selectPaymentAmount } from "@/lib/payments/personal-payment";
import type { CreateOrderInput } from "@/lib/validation";

type ReleaseRow = {
  platform: "android" | "ios";
  channel: string;
  version_name: string;
  version_code: number;
  minimum_os: string;
  artifact_path: string | null;
  store_url: string | null;
  sha256: string | null;
  file_size: number | null;
  release_notes: string;
  status: string;
  published_at: string | null;
};

type OrderRow = {
  id: string;
  order_no: string;
  result_session_hash: string;
  buyer_email_ciphertext: string;
  buyer_email_hash: string;
  platform: "android" | "ios";
  device_id_ciphertext: string;
  device_id_hash: string;
  device_id_suffix: string;
  list_amount_minor: number | null;
  amount_minor: number;
  currency: string;
  payment_provider: PaymentProvider;
  payment_trade_no: string | null;
  payment_qr_code: string | null;
  payment_event_id: string | null;
  status: string;
  paid_at: string | null;
  expires_at: string | null;
  reservation_expires_at: string | null;
  created_at: string;
};

type LicenseRow = {
  credential_ciphertext: string;
  signing_key_id: string;
  is_development: number;
  issued_at: number;
};

export type ReleaseDTO = {
  platform: "android" | "ios";
  channel: string;
  versionName: string;
  versionCode: number;
  minimumOS: string;
  available: boolean;
  storeUrl: string | null;
  sha256: string | null;
  fileSize: number | null;
  releaseNotes: string;
  publishedAt: string | null;
};

export type OrderDTO = {
  orderNo: string;
  platform: "android" | "ios";
  deviceID: string;
  deviceIDSuffix: string;
  status: string;
  listAmountMinor: number;
  amountMinor: number;
  currency: string;
  paymentProvider: PaymentProvider;
  createdAt: string;
  paidAt: string | null;
  expiresAt: string | null;
  paymentQrCode: string | null;
  paymentAvailable: boolean;
  credential: string | null;
  signingKeyId: string | null;
  isDevelopmentCredential: boolean;
  issuedAt: number | null;
};

function transaction<T>(work: () => T): T {
  db.exec("BEGIN IMMEDIATE");
  try {
    const result = work();
    db.exec("COMMIT");
    return result;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

function makeOrderNo(): string {
  const date = new Date().toISOString().slice(0, 10).replaceAll("-", "");
  return `FLM-${date}-${randomBytes(4).toString("hex").toUpperCase()}`;
}

function orderByNumber(orderNo: string): OrderRow | undefined {
  return db
    .prepare("SELECT * FROM orders WHERE order_no = ?")
    .get(orderNo) as OrderRow | undefined;
}

function expireStalePersonalOrders(now: string): void {
  db.prepare(
    `UPDATE orders
     SET status = 'expired', updated_at = ?
     WHERE payment_provider = 'personal_alipay_monitor'
       AND status = 'pending'
       AND reservation_expires_at IS NOT NULL
       AND reservation_expires_at < ?`,
  ).run(now, now);
  db.prepare(
    `DELETE FROM payment_amount_reservations
     WHERE expires_at < ?`,
  ).run(now);
}

function availablePersonalPaymentAmount(createdAt: string): number {
  const occupiedRows = db
    .prepare(
      `SELECT amount_minor
       FROM payment_amount_reservations
       WHERE provider = 'personal_alipay_monitor'
         AND expires_at >= ?`,
    )
    .all(createdAt) as Array<{ amount_minor: number }>;
  const amountMinor = selectPaymentAmount(
    siteConfig.priceMinor,
    siteConfig.personalPaymentDiscountMaxMinor,
    new Set(occupiedRows.map((row) => row.amount_minor)),
  );
  return amountMinor;
}

function reservePersonalPaymentAmount(
  orderId: string,
  amountMinor: number,
  createdAt: string,
  reservationExpiresAt: string,
): void {
  db.prepare(
    `INSERT INTO payment_amount_reservations (
      id, provider, amount_minor, order_id, expires_at, created_at
    ) VALUES (?, ?, ?, ?, ?, ?)`,
  ).run(
    randomUUID(),
    "personal_alipay_monitor",
    amountMinor,
    orderId,
    reservationExpiresAt,
    createdAt,
  );
}

function sessionCanRead(order: OrderRow, sessionToken: string): boolean {
  return hashesMatch(order.result_session_hash, tokenHash(sessionToken));
}

export function getLatestRelease(
  platform: "android" | "ios",
): ReleaseDTO | null {
  const row = db
    .prepare(
      `SELECT * FROM releases
       WHERE platform = ?
       ORDER BY CASE status WHEN 'published' THEN 0 ELSE 1 END,
                updated_at DESC
       LIMIT 1`,
    )
    .get(platform) as ReleaseRow | undefined;
  if (!row) return null;
  return {
    platform: row.platform,
    channel: row.channel,
    versionName: row.version_name,
    versionCode: row.version_code,
    minimumOS: row.minimum_os,
    available:
      row.status === "published" &&
      Boolean(row.artifact_path || row.store_url),
    storeUrl: row.store_url,
    sha256: row.sha256,
    fileSize: row.file_size,
    releaseNotes: row.release_notes,
    publishedAt: row.published_at,
  };
}

export function getAndroidArtifactPath(): string | null {
  const row = db
    .prepare(
      `SELECT artifact_path FROM releases
       WHERE platform = 'android' AND status = 'published'
       ORDER BY updated_at DESC LIMIT 1`,
    )
    .get() as { artifact_path: string | null } | undefined;
  return row?.artifact_path ?? null;
}

export function createOrder(
  input: CreateOrderInput,
  idempotencyKey: string,
): { orderNo: string; sessionToken: string } {
  const sessionToken = createSessionToken();
  const nowDate = new Date();
  const now = nowDate.toISOString();

  return transaction(() => {
    expireStalePersonalOrders(now);
    const existing = db
      .prepare("SELECT order_no FROM orders WHERE idempotency_key = ?")
      .get(idempotencyKey) as { order_no: string } | undefined;
    if (existing) {
      db.prepare(
        "UPDATE orders SET result_session_hash = ?, updated_at = ? WHERE order_no = ?",
      ).run(tokenHash(sessionToken), now, existing.order_no);
      return { orderNo: existing.order_no, sessionToken };
    }

    const paymentProvider = siteConfig.paymentProvider;
    const emailHash = lookupHash(input.email);
    const deviceIdHash = lookupHash(input.deviceID);
    if (paymentProvider === "personal_alipay_monitor") {
      const rateWindowStart = new Date(
        nowDate.getTime() - 60 * 1_000,
      ).toISOString();
      const recentOrderCount = db
        .prepare(
          `SELECT COUNT(*) AS count FROM orders
           WHERE payment_provider = 'personal_alipay_monitor'
             AND created_at >= ?`,
        )
        .get(rateWindowStart) as { count: number };
      if (
        recentOrderCount.count >=
        siteConfig.personalPaymentMaxOrdersPerMinute
      ) {
        throw new Error("ORDER_RATE_LIMITED");
      }

      const activeBuyerOrderCount = db
        .prepare(
          `SELECT COUNT(*) AS count FROM orders
           WHERE payment_provider = 'personal_alipay_monitor'
             AND status = 'pending'
             AND reservation_expires_at >= ?
             AND (buyer_email_hash = ? OR device_id_hash = ?)`,
        )
        .get(now, emailHash, deviceIdHash) as { count: number };
      if (
        activeBuyerOrderCount.count >=
        siteConfig.personalPaymentMaxActiveOrdersPerBuyer
      ) {
        throw new Error("BUYER_ACTIVE_ORDER_LIMIT_REACHED");
      }
    }

    const orderId = randomUUID();
    const orderNo = makeOrderNo();
    const expiresAt =
      paymentProvider === "personal_alipay_monitor"
        ? new Date(
            nowDate.getTime() +
              siteConfig.personalPaymentLifetimeSeconds * 1_000,
          ).toISOString()
        : null;
    const reservationExpiresAt =
      expiresAt === null
        ? null
        : new Date(
            new Date(expiresAt).getTime() +
              siteConfig.personalPaymentGraceSeconds * 1_000,
          ).toISOString();
    const amountMinor =
      paymentProvider === "personal_alipay_monitor" &&
      reservationExpiresAt !== null
        ? availablePersonalPaymentAmount(now)
        : siteConfig.priceMinor;

    db.prepare(
      `INSERT INTO orders (
        id, order_no, idempotency_key, result_session_hash,
        buyer_email_ciphertext, buyer_email_hash, platform,
        device_id_ciphertext, device_id_hash, device_id_suffix,
        list_amount_minor, amount_minor, currency, payment_provider,
        payment_qr_code, status, terms_version, expires_at,
        reservation_expires_at, created_at, updated_at
      ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      )`,
    ).run(
      orderId,
      orderNo,
      idempotencyKey,
      tokenHash(sessionToken),
      encrypt(input.email),
      emailHash,
      input.platform,
      encrypt(input.deviceID),
      deviceIdHash,
      input.deviceID.slice(-4),
      siteConfig.priceMinor,
      amountMinor,
      siteConfig.currency,
      paymentProvider,
      paymentProvider === "personal_alipay_monitor"
        ? "personal-alipay-collection-code"
        : null,
      "pending",
      "2026-08-16",
      expiresAt,
      reservationExpiresAt,
      now,
      now,
    );
    if (
      paymentProvider === "personal_alipay_monitor" &&
      reservationExpiresAt !== null
    ) {
      reservePersonalPaymentAmount(
        orderId,
        amountMinor,
        now,
        reservationExpiresAt,
      );
    }
    return { orderNo, sessionToken };
  });
}

export function getOrder(
  orderNo: string,
  sessionToken: string,
): OrderDTO | null {
  const now = new Date().toISOString();
  expireStalePersonalOrders(now);
  const order = orderByNumber(orderNo);
  if (!order || !sessionCanRead(order, sessionToken)) return null;
  const paymentDeadlinePassed =
    order.payment_provider === "personal_alipay_monitor" &&
    order.status === "pending" &&
    order.expires_at !== null &&
    order.expires_at < now;
  const status = paymentDeadlinePassed ? "expired" : order.status;
  const license = db
    .prepare("SELECT * FROM licenses WHERE order_id = ?")
    .get(order.id) as LicenseRow | undefined;
  return {
    orderNo: order.order_no,
    platform: order.platform,
    deviceID: decrypt(order.device_id_ciphertext),
    deviceIDSuffix: order.device_id_suffix,
    status,
    listAmountMinor: order.list_amount_minor ?? order.amount_minor,
    amountMinor: order.amount_minor,
    currency: order.currency,
    paymentProvider: order.payment_provider,
    createdAt: order.created_at,
    paidAt: order.paid_at,
    expiresAt: order.expires_at,
    paymentQrCode: order.payment_qr_code,
    paymentAvailable:
      status === "pending" && Boolean(order.payment_qr_code),
    credential: license ? decrypt(license.credential_ciphertext) : null,
    signingKeyId: license?.signing_key_id ?? null,
    isDevelopmentCredential: license?.is_development === 1,
    issuedAt: license?.issued_at ?? null,
  };
}

export function getPaymentQrCode(orderNo: string): string | null {
  const order = orderByNumber(orderNo);
  if (!order || order.payment_provider !== "alipay") {
    throw new Error("ORDER_NOT_FOUND");
  }
  return order.payment_qr_code;
}

export function savePaymentQrCode(orderNo: string, qrCode: string): void {
  const result = db
    .prepare(
      `UPDATE orders SET payment_qr_code = ?, updated_at = ?
       WHERE order_no = ? AND payment_provider = 'alipay'
         AND status = 'pending'`,
    )
    .run(qrCode, new Date().toISOString(), orderNo);
  if (result.changes !== 1) {
    throw new Error("ORDER_NOT_PAYABLE");
  }
}

function issueLicenseForOrder(order: OrderRow, now: string): void {
  const signed = signDeviceCredential(decrypt(order.device_id_ciphertext));
  db.prepare(
    `INSERT INTO licenses (
      id, order_id, device_id_hash, credential_ciphertext,
      protocol_version, signing_key_id, is_development, issued_at,
      status, created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
  ).run(
    randomUUID(),
    order.id,
    order.device_id_hash,
    encrypt(signed.credential),
    1,
    signed.signingKeyId,
    signed.isDevelopmentCredential ? 1 : 0,
    signed.issuedAt,
    "issued",
    now,
  );
  db.prepare(
    `UPDATE orders
     SET status = 'fulfilled', updated_at = ?
     WHERE id = ?`,
  ).run(now, order.id);
  db.prepare(
    "DELETE FROM payment_amount_reservations WHERE order_id = ?",
  ).run(order.id);
}

export function fulfillAlipayPayment(input: {
  orderNo: string;
  tradeNo: string;
  amountMinor: number;
}): void {
  return transaction(() => {
    const order = orderByNumber(input.orderNo);
    if (!order || order.payment_provider !== "alipay") {
      throw new Error("ORDER_NOT_FOUND");
    }
    if (order.status === "fulfilled") {
      if (order.payment_trade_no !== input.tradeNo) {
        throw new Error("ORDER_ALREADY_PAID");
      }
      return;
    }
    if (order.status !== "pending") {
      throw new Error("ORDER_NOT_PAYABLE");
    }
    if (order.amount_minor !== input.amountMinor || order.currency !== "CNY") {
      throw new Error("ORDER_AMOUNT_MISMATCH");
    }

    const now = new Date().toISOString();
    db.prepare(
      `UPDATE orders SET status = 'issuing', payment_trade_no = ?,
       paid_at = ?, updated_at = ? WHERE id = ?`,
    ).run(input.tradeNo, now, now, order.id);
    db.prepare(
      `INSERT INTO payment_events (
        id, provider, provider_event_id, order_id, signature_verified,
        process_status, received_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      randomUUID(),
      "alipay",
      `alipay:${input.tradeNo}`,
      order.id,
      1,
      "processed",
      now,
    );

    issueLicenseForOrder(order, now);
  });
}

export type MonitorPaymentProcessResult =
  | "matched"
  | "unmatched"
  | "manual_review"
  | "duplicate";

export function processMonitorPaymentEvent(input: {
  event: MonitorPaymentEvent;
  monitorId: string;
  nonce: string;
  receivedAt?: Date;
}): MonitorPaymentProcessResult {
  const receivedAt = input.receivedAt ?? new Date();
  const receivedAtIso = receivedAt.toISOString();
  const observedAtIso = new Date(input.event.observedAt).toISOString();
  const providerEventId =
    `monitor:${input.monitorId}:${input.event.eventId}`;

  return transaction(() => {
    expireStalePersonalOrders(receivedAtIso);
    const duplicate = db
      .prepare(
        `SELECT id FROM monitor_payment_events
         WHERE provider_event_id = ? OR nonce = ?`,
      )
      .get(providerEventId, input.nonce);
    if (duplicate) return "duplicate";

    const eventId = randomUUID();
    db.prepare(
      `INSERT INTO monitor_payment_events (
        id, provider_event_id, source, monitor_id, nonce, channel,
        amount_minor, observed_at, notification_hash,
        source_authenticated, provider_verified, process_status,
        received_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    ).run(
      eventId,
      providerEventId,
      "android_notification_monitor",
      input.monitorId,
      input.nonce,
      input.event.channel,
      input.event.amountMinor,
      observedAtIso,
      input.event.notificationHash.toLowerCase(),
      1,
      0,
      "received",
      receivedAtIso,
    );

    const matches = db
      .prepare(
        `SELECT * FROM orders
         WHERE payment_provider = 'personal_alipay_monitor'
           AND amount_minor = ?
           AND status = 'pending'
           AND created_at <= ?
           AND reservation_expires_at >= ?
         ORDER BY created_at ASC`,
      )
      .all(
        input.event.amountMinor,
        observedAtIso,
        observedAtIso,
      ) as OrderRow[];

    if (matches.length === 0) {
      db.prepare(
        `UPDATE monitor_payment_events
         SET process_status = 'unmatched', processed_at = ?
         WHERE id = ?`,
      ).run(receivedAtIso, eventId);
      return "unmatched";
    }

    if (matches.length > 1) {
      db.prepare(
        `UPDATE monitor_payment_events
         SET process_status = 'manual_review', processed_at = ?
         WHERE id = ?`,
      ).run(receivedAtIso, eventId);
      for (const order of matches) {
        db.prepare(
          `UPDATE orders SET status = 'manual_review', updated_at = ?
           WHERE id = ?`,
        ).run(receivedAtIso, order.id);
      }
      return "manual_review";
    }

    const order = matches[0];
    db.prepare(
      `UPDATE monitor_payment_events
       SET process_status = 'matched', order_id = ?, processed_at = ?
       WHERE id = ?`,
    ).run(order.id, receivedAtIso, eventId);
    db.prepare(
      `UPDATE orders
       SET status = 'issuing', payment_event_id = ?, paid_at = ?,
           updated_at = ?
       WHERE id = ? AND status = 'pending'`,
    ).run(providerEventId, observedAtIso, receivedAtIso, order.id);
    issueLicenseForOrder(order, receivedAtIso);
    return "matched";
  });
}

export function recoverOrderSession(
  orderNo: string,
  normalizedEmail: string,
): { orderNo: string; sessionToken: string } | null {
  const order = orderByNumber(orderNo);
  if (
    !order ||
    order.status !== "fulfilled" ||
    !hashesMatch(order.buyer_email_hash, lookupHash(normalizedEmail))
  ) {
    return null;
  }
  const sessionToken = createSessionToken();
  db.prepare(
    "UPDATE orders SET result_session_hash = ?, updated_at = ? WHERE id = ?",
  ).run(tokenHash(sessionToken), new Date().toISOString(), order.id);
  return { orderNo: order.order_no, sessionToken };
}

export function listRecentOrders(): Array<{
  orderNo: string;
  platform: string;
  deviceIDSuffix: string;
  status: string;
  amountMinor: number;
  currency: string;
  createdAt: string;
}> {
  const rows = db
    .prepare(
      `SELECT order_no, platform, device_id_suffix, status,
              amount_minor, currency, created_at
       FROM orders ORDER BY created_at DESC LIMIT 20`,
    )
    .all() as Array<{
    order_no: string;
    platform: string;
    device_id_suffix: string;
    status: string;
    amount_minor: number;
    currency: string;
    created_at: string;
  }>;
  return rows.map((row) => ({
    orderNo: row.order_no,
    platform: row.platform,
    deviceIDSuffix: row.device_id_suffix,
    status: row.status,
    amountMinor: row.amount_minor,
    currency: row.currency,
    createdAt: row.created_at,
  }));
}
