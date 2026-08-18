"use client";

import { CircleCheckBig, LoaderCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

export function PaymentClaimButton({ orderNo }: { orderNo: string }) {
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function claimPayment() {
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/orders/${encodeURIComponent(orderNo)}/payment-claimed`,
        {
          method: "POST",
          credentials: "same-origin",
          headers: { "Content-Type": "application/json" },
          body: "{}",
        },
      );
      const result = (await response.json()) as { errorCode?: string };
      if (!response.ok) {
        throw new Error(
          result.errorCode === "PAYMENT_CLAIM_LIMIT_REACHED"
            ? "提交次数已达上限，请联系管理员"
            : result.errorCode === "ORDER_NOT_CLAIMABLE"
              ? "订单已过期或当前状态无法提交"
              : "提交失败，请稍后重试",
        );
      }
      router.refresh();
    } catch (submissionError) {
      setError(
        submissionError instanceof Error
          ? submissionError.message
          : "提交失败，请稍后重试",
      );
      setSubmitting(false);
    }
  }

  return (
    <div className="payment-claim">
      <button
        className="button button--primary button--full"
        type="button"
        disabled={submitting}
        onClick={claimPayment}
      >
        {submitting ? (
          <LoaderCircle className="spin" size={18} />
        ) : (
          <CircleCheckBig size={18} />
        )}
        {submitting ? "正在提交" : "我已付款"}
      </button>
      {error ? (
        <p className="form-error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
