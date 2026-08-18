#!/usr/bin/env node

import {
  createPrivateKey,
  createPublicKey,
} from "node:crypto";
import { readFileSync, statSync } from "node:fs";
import path from "node:path";

const ED25519_PKCS8_PREFIX = Buffer.from(
  "302e020100300506032b657004220420",
  "hex",
);
const EXPECTED_ACTIVATION_PUBLIC_KEY =
  "81C0KEAE1R38JV0E5LI5x3tuZgzwUK8FGQv4w62dcBQ=";

function fail(message) {
  process.stderr.write(`[FAIL] ${message}\n`);
  process.exitCode = 1;
}

function ok(message) {
  process.stdout.write(`[OK] ${message}\n`);
}

function parseEnvironment(filePath) {
  const values = {};
  const content = readFileSync(filePath, "utf8");
  for (const sourceLine of content.split(/\r?\n/)) {
    const line = sourceLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator < 1) continue;
    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    values[key] = value.replaceAll("\\n", "\n");
  }
  return values;
}

function requireValue(values, key, minimumLength = 1) {
  const value = values[key];
  if (!value || value.length < minimumLength) {
    fail(`${key} 未配置或长度不足`);
    return null;
  }
  ok(`${key} 已配置`);
  return value;
}

function requireInteger(values, key, minimum, maximum) {
  const value = Number(values[key]);
  if (
    !Number.isSafeInteger(value) ||
    value < minimum ||
    value > maximum
  ) {
    fail(`${key} 必须是 ${minimum}..${maximum} 的整数`);
    return null;
  }
  ok(`${key}=${value}`);
  return value;
}

function decodeBase64(value, expectedLength, key) {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    fail(`${key} 不是标准 Base64`);
    return null;
  }
  const decoded = Buffer.from(value, "base64");
  if (decoded.length !== expectedLength) {
    fail(`${key} 解码后必须是 ${expectedLength} 字节`);
    return null;
  }
  return decoded;
}

const envPath = path.resolve(process.argv[2] ?? "");
const appRoot = path.resolve(process.argv[3] ?? process.cwd());

if (!process.argv[2]) {
  process.stderr.write(
    "usage: validate_config.mjs <environment-file> [app-root]\n",
  );
  process.exit(2);
}

let values;
try {
  values = parseEnvironment(envPath);
  const mode = statSync(envPath).mode & 0o777;
  if ((mode & 0o077) !== 0) {
    fail("环境文件权限过宽，应使用 chmod 600");
  } else {
    ok("环境文件权限为 owner-only");
  }
} catch (error) {
  fail(`无法读取环境文件：${error.message}`);
  process.exit(1);
}

const provider = values.PAYMENT_PROVIDER;
if (provider !== "personal_alipay_monitor" && provider !== "alipay") {
  fail("PAYMENT_PROVIDER 必须是 personal_alipay_monitor 或 alipay");
} else {
  ok(`PAYMENT_PROVIDER=${provider}`);
}

const productPrice = requireInteger(
  values,
  "PRODUCT_PRICE_MINOR",
  1,
  100_000_000,
);
const lifetime = requireInteger(
  values,
  "PERSONAL_PAYMENT_LIFETIME_SECONDS",
  60,
  86_400,
);
const grace = requireInteger(
  values,
  "PERSONAL_PAYMENT_GRACE_SECONDS",
  0,
  3_600,
);
const maxOrdersPerMinute = requireInteger(
  values,
  "PERSONAL_PAYMENT_MAX_ORDERS_PER_MINUTE",
  1,
  100,
);
requireInteger(
  values,
  "PERSONAL_PAYMENT_MAX_ACTIVE_ORDERS_PER_BUYER",
  1,
  10,
);
if (
  lifetime !== null &&
  grace !== null &&
  maxOrdersPerMinute !== null
) {
  const reservationMinutes = Math.ceil((lifetime + grace) / 60);
  const initialAmountPoolSize = Math.min(productPrice ?? 1, 101);
  if (maxOrdersPerMinute * reservationMinutes >= initialAmountPoolSize) {
    fail(
      "订单创建速率过高，持续请求可能占满初始价格的唯一金额池；请降低 " +
        "PERSONAL_PAYMENT_MAX_ORDERS_PER_MINUTE 或提高 PRODUCT_PRICE_MINOR",
    );
  } else {
    ok("订单限流配置不会在一个占用周期内耗尽金额池");
  }
}

const dataKey = requireValue(values, "DATA_ENCRYPTION_KEY_BASE64");
if (dataKey) decodeBase64(dataKey, 32, "DATA_ENCRYPTION_KEY_BASE64");
requireValue(values, "LOOKUP_HMAC_PEPPER", 32);
requireValue(values, "ACTIVATION_SIGNING_KEY_ID", 1);

const appBaseUrl = requireValue(values, "APP_BASE_URL");
if (appBaseUrl) {
  try {
    const url = new URL(appBaseUrl);
    if (url.protocol !== "https:") {
      fail("APP_BASE_URL 必须使用 HTTPS");
    } else if (url.hostname.endsWith(".example")) {
      fail("APP_BASE_URL 必须替换为真实公网域名");
    } else {
      ok("APP_BASE_URL 是有效的 HTTPS 公网地址");
    }
  } catch {
    fail("APP_BASE_URL 不是有效 URL");
  }
}

const activationSeedValue = requireValue(
  values,
  "ACTIVATION_PRIVATE_KEY_BASE64",
);
if (activationSeedValue) {
  const seed = decodeBase64(
    activationSeedValue,
    32,
    "ACTIVATION_PRIVATE_KEY_BASE64",
  );
  if (seed) {
    const privateKey = createPrivateKey({
      key: Buffer.concat([ED25519_PKCS8_PREFIX, seed]),
      format: "der",
      type: "pkcs8",
    });
    const publicDer = createPublicKey(privateKey).export({
      format: "der",
      type: "spki",
    });
    const publicKey = Buffer.from(publicDer).subarray(-32).toString("base64");
    if (publicKey !== EXPECTED_ACTIVATION_PUBLIC_KEY) {
      fail("激活私钥与 Android/iOS 内置公钥不匹配");
    } else {
      ok("激活私钥与 App 内置公钥匹配");
    }
  }
}

if (provider === "personal_alipay_monitor") {
  const monitorId = requireValue(values, "PAYMENT_MONITOR_ID", 8);
  if (monitorId && !/^[A-Za-z0-9_-]{8,128}$/.test(monitorId)) {
    fail("PAYMENT_MONITOR_ID 只能包含字母、数字、下划线和连字符");
  }
  requireValue(values, "PAYMENT_MONITOR_SECRET", 32);
}

if (provider === "alipay") {
  for (const key of [
    "ALIPAY_APP_ID",
    "ALIPAY_SELLER_ID",
    "ALIPAY_PRIVATE_KEY",
    "ALIPAY_PUBLIC_KEY",
  ]) {
    requireValue(values, key);
  }
}

for (const [key, fallback] of [
  ["PERSONAL_ALIPAY_QR_IMAGE_PATH", "./asset/20260816-195133.jpeg"],
  [
    "ANDROID_APK_PATH",
    "./artifacts/outputs/apk/release/FilmLightMeter-release.apk",
  ],
]) {
  const configuredPath = values[key] || fallback;
  const resolvedPath = path.resolve(appRoot, configuredPath);
  try {
    const stat = statSync(resolvedPath);
    if (!stat.isFile() || stat.size === 0) throw new Error("不是有效文件");
    ok(`${key} 文件存在`);
    if (
      key === "ANDROID_APK_PATH" &&
      (/debug|unsigned/i.test(configuredPath) ||
        !/release/i.test(configuredPath))
    ) {
      fail("ANDROID_APK_PATH 必须指向已签名 Release APK");
    }
  } catch (error) {
    fail(`${key} 无法读取：${resolvedPath} (${error.message})`);
  }
}

if (process.exitCode) process.exit(process.exitCode);
process.stdout.write("\n生产配置检查通过。\n");
