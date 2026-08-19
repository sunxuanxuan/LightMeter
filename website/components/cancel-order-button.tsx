"use client";

import { LoaderCircle, XCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

export function CancelOrderButton({ orderNo }: { orderNo: string }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function cancelOrder() {
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/orders/${encodeURIComponent(orderNo)}/cancel`,
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
          result.errorCode === "ORDER_NOT_CANCELLABLE"
            ? "订单状态已变化，当前不能取消"
            : "取消失败，请稍后重试",
        );
      }
      router.refresh();
    } catch (submissionError) {
      setError(
        submissionError instanceof Error
          ? submissionError.message
          : "取消失败，请稍后重试",
      );
      setSubmitting(false);
    }
  }

  if (!confirming) {
    return (
      <button
        className="button button--secondary"
        type="button"
        onClick={() => setConfirming(true)}
      >
        <XCircle size={16} />
        取消订单
      </button>
    );
  }

  return (
    <div className="cancel-order-confirmation">
      <span>仅在尚未付款时取消。</span>
      <button
        className="button button--secondary"
        type="button"
        disabled={submitting}
        onClick={() => setConfirming(false)}
      >
        保留订单
      </button>
      <button
        className="button button--danger"
        type="button"
        disabled={submitting}
        onClick={cancelOrder}
      >
        {submitting ? <LoaderCircle className="spin" size={16} /> : null}
        {submitting ? "正在取消" : "确认取消"}
      </button>
      {error ? (
        <span className="form-error" role="alert">
          {error}
        </span>
      ) : null}
    </div>
  );
}
