import { z } from "zod";

export const monitorPricingUpdateSchema = z.object({
  priceMinor: z.number().int().min(1).max(100_000_000),
  monitorVersion: z.string().trim().min(1).max(64),
});

export type MonitorPricingUpdate = z.infer<
  typeof monitorPricingUpdateSchema
>;
