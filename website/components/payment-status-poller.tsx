"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

export function PaymentStatusPoller({
  active,
  orderNo,
  status,
}: {
  active: boolean;
  orderNo: string;
  status: string;
}) {
  const router = useRouter();

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    let timer: number | undefined;

    async function poll() {
      try {
        const response = await fetch(
          `/api/orders/${encodeURIComponent(orderNo)}`,
          { cache: "no-store" },
        );
        if (response.ok) {
          const result = (await response.json()) as { status?: string };
          if (result.status && result.status !== status) {
            if (
              result.status === "fulfilled" ||
              result.status === "expired" ||
              result.status === "manual_review"
            ) {
              cancelled = true;
            }
            router.refresh();
            return;
          }
        }
      } catch {
        // A later poll handles transient network failures.
      } finally {
        if (!cancelled) timer = window.setTimeout(poll, 2_000);
      }
    }

    timer = window.setTimeout(poll, 2_000);
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [active, orderNo, router, status]);

  return null;
}
