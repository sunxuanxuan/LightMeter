import { z } from "zod";

export const monitorConfirmationDecisionSchema = z.object({
  decision: z.enum(["confirm", "reject"]),
  monitorVersion: z.string().trim().min(1).max(64),
});
