import "server-only";

import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";

type DatabaseGlobal = typeof globalThis & {
  __filmLightMeterDatabase?: DatabaseSync;
};

function initializeDatabase(): DatabaseSync {
  const dataDirectory = path.resolve(
    /* turbopackIgnore: true */
    process.cwd(),
    process.env.SITE_DATA_DIR ?? "data",
  );
  mkdirSync(dataDirectory, { recursive: true });
  const database = new DatabaseSync(
    path.join(dataDirectory, "filmlightmeter.db"),
  );
  database.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    PRAGMA busy_timeout = 5000;

    CREATE TABLE IF NOT EXISTS releases (
      id TEXT PRIMARY KEY,
      platform TEXT NOT NULL,
      channel TEXT NOT NULL,
      version_name TEXT NOT NULL,
      version_code INTEGER NOT NULL,
      minimum_os TEXT NOT NULL,
      artifact_path TEXT,
      store_url TEXT,
      sha256 TEXT,
      file_size INTEGER,
      release_notes TEXT NOT NULL,
      status TEXT NOT NULL,
      published_at TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS orders (
      id TEXT PRIMARY KEY,
      order_no TEXT NOT NULL UNIQUE,
      idempotency_key TEXT NOT NULL UNIQUE,
      result_session_hash TEXT NOT NULL,
      buyer_email_ciphertext TEXT NOT NULL,
      buyer_email_hash TEXT NOT NULL,
      platform TEXT NOT NULL,
      device_id_ciphertext TEXT NOT NULL,
      device_id_hash TEXT NOT NULL,
      device_id_suffix TEXT NOT NULL,
      amount_minor INTEGER NOT NULL,
      currency TEXT NOT NULL,
      payment_provider TEXT NOT NULL,
      payment_trade_no TEXT UNIQUE,
      status TEXT NOT NULL,
      terms_version TEXT NOT NULL,
      paid_at TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_orders_email_hash
      ON orders(buyer_email_hash);
    CREATE INDEX IF NOT EXISTS idx_orders_status
      ON orders(status);

    CREATE TABLE IF NOT EXISTS licenses (
      id TEXT PRIMARY KEY,
      order_id TEXT NOT NULL UNIQUE,
      device_id_hash TEXT NOT NULL,
      credential_ciphertext TEXT NOT NULL,
      protocol_version INTEGER NOT NULL,
      signing_key_id TEXT NOT NULL,
      is_development INTEGER NOT NULL,
      issued_at INTEGER NOT NULL,
      status TEXT NOT NULL,
      created_at TEXT NOT NULL,
      FOREIGN KEY(order_id) REFERENCES orders(id)
    );

    CREATE TABLE IF NOT EXISTS payment_events (
      id TEXT PRIMARY KEY,
      provider TEXT NOT NULL,
      provider_event_id TEXT NOT NULL UNIQUE,
      order_id TEXT NOT NULL,
      signature_verified INTEGER NOT NULL,
      process_status TEXT NOT NULL,
      received_at TEXT NOT NULL,
      FOREIGN KEY(order_id) REFERENCES orders(id)
    );
  `);
  seedLocalReleases(database);
  return database;
}

function seedLocalReleases(database: DatabaseSync) {
  const now = new Date().toISOString();
  const configuredPath =
    process.env.ANDROID_APK_PATH ??
    "../android/app/build/outputs/apk/release/app-release.apk";
  const apkPath = path.resolve(
    /* turbopackIgnore: true */
    process.cwd(),
    configuredPath,
  );
  const normalizedApkPath = apkPath.replaceAll("\\", "/").toLowerCase();
  const isReleaseArtifact =
    normalizedApkPath.includes("/outputs/apk/release/") &&
    !normalizedApkPath.includes("debug") &&
    !normalizedApkPath.includes("unsigned");
  const artifactAvailable = isReleaseArtifact && existsSync(apkPath);
  const artifact = artifactAvailable ? readFileSync(apkPath) : null;
  const sha256 = artifact
    ? createHash("sha256").update(artifact).digest("hex")
    : null;
  const fileSize = artifactAvailable ? statSync(apkPath).size : null;

  database
    .prepare(
      `INSERT INTO releases (
        id, platform, channel, version_name, version_code, minimum_os,
        artifact_path, store_url, sha256, file_size, release_notes, status,
        published_at, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        channel = excluded.channel,
        version_name = excluded.version_name,
        version_code = excluded.version_code,
        minimum_os = excluded.minimum_os,
        artifact_path = excluded.artifact_path,
        sha256 = excluded.sha256,
        file_size = excluded.file_size,
        release_notes = excluded.release_notes,
        status = excluded.status,
        published_at = excluded.published_at,
        updated_at = excluded.updated_at`,
    )
    .run(
      "local-android-preview",
      "android",
      "production",
      "0.1.0",
      1,
      "Android 8.0",
      artifactAvailable ? apkPath : null,
      null,
      sha256,
      fileSize,
      "最新正式版本，包含完整测光功能和离线激活。",
      artifactAvailable ? "published" : "draft",
      artifactAvailable ? now : null,
      now,
      now,
    );
}

const databaseGlobal = globalThis as DatabaseGlobal;

export const db =
  databaseGlobal.__filmLightMeterDatabase ?? initializeDatabase();

if (process.env.NODE_ENV !== "production") {
  databaseGlobal.__filmLightMeterDatabase = db;
}
