import { z } from "zod";

export const monitorPaymentEventSchema = z.object({
  eventId: z.string().trim().min(16).max(128),
  channel: z.literal("alipay"),
  amountMinor: z.number().int().min(1).max(100_000_000),
  observedAt: z.number().int().min(0),
  notificationHash: z.string().regex(/^[0-9a-f]{64}$/i),
  monitorVersion: z.string().trim().min(1).max(64),
});

export type MonitorPaymentEvent = z.infer<typeof monitorPaymentEventSchema>;
