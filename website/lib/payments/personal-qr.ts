import "server-only";

import { access, readFile } from "node:fs/promises";
import path from "node:path";

import { siteConfig } from "@/lib/config";

function imagePath(): string {
  return path.resolve(
    /* turbopackIgnore: true */
    process.cwd(),
    siteConfig.personalPaymentQrImagePath,
  );
}

export async function assertPersonalAlipayQrImage(): Promise<void> {
  await access(imagePath());
}

export async function readPersonalAlipayQrImage(): Promise<Uint8Array> {
  return new Uint8Array(await readFile(imagePath()));
}
