import { z } from "zod";

export const platformSchema = z.enum(["android", "ios"]);
export const publicPlatformSchema = z.literal("android");

export function normalizeDeviceId(value: string): string {
  return value.replace(/[\s-]/g, "").toUpperCase();
}

export function formatDeviceId(value: string): string {
  return normalizeDeviceId(value).match(/.{1,4}/g)?.join(" ") ?? "";
}

export const deviceIdSchema = z
  .string()
  .transform(normalizeDeviceId)
  .pipe(z.string().regex(/^[0-9A-F]{16}$/, "设备 ID 必须是 16 位十六进制字符"));

export const createOrderSchema = z.object({
  platform: publicPlatformSchema,
  deviceID: deviceIdSchema,
  email: z
    .string()
    .trim()
    .toLowerCase()
    .pipe(z.email("请输入有效邮箱")),
  acceptedTerms: z.literal(true, {
    error: "请先确认单设备授权与换机说明",
  }),
});

export const recoverOrderSchema = z.object({
  orderNo: z.string().trim().min(8).max(40),
  email: z.string().trim().toLowerCase().pipe(z.email()),
});

export type CreateOrderInput = z.infer<typeof createOrderSchema>;
