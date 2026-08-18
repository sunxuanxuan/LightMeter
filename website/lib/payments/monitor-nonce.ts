import "server-only";

import { db } from "@/lib/db";

const NONCE_RETENTION_MS = 5 * 60 * 1_000;

export function consumeMonitorRequestNonce(input: {
  monitorId: string;
  nonce: string;
  method: string;
  path: string;
  usedAt: Date;
}): boolean {
  const usedAt = input.usedAt.toISOString();
  const expiresAt = new Date(
    input.usedAt.getTime() + NONCE_RETENTION_MS,
  ).toISOString();

  db.exec("BEGIN IMMEDIATE");
  try {
    db.prepare(
      "DELETE FROM monitor_request_nonces WHERE expires_at < ?",
    ).run(usedAt);
    const result = db
      .prepare(
        `INSERT OR IGNORE INTO monitor_request_nonces (
          monitor_id, nonce, method, path, used_at, expires_at
        ) VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(
        input.monitorId,
        input.nonce,
        input.method,
        input.path,
        usedAt,
        expiresAt,
      );
    db.exec("COMMIT");
    return result.changes === 1;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}
