"use client";

import { BadgeCheck, LoaderCircle } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

export function SimulatePaymentButton({ orderNo }: { orderNo: string }) {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function simulate() {
    setLoading(true);
    setError(null);
    const response = await fetch(
      `/api/orders/${encodeURIComponent(orderNo)}/simulate-payment`,
      { method: "POST" },
    );
    if (!response.ok) {
      setError("模拟付款失败，请刷新后重试");
      setLoading(false);
      return;
    }
    router.refresh();
  }

  return (
    <>
      <button
        className="button button--primary button--full"
        type="button"
        onClick={simulate}
        disabled={loading}
      >
        {loading ? (
          <LoaderCircle className="spin" size={18} />
        ) : (
          <BadgeCheck size={18} />
        )}
        {loading ? "正在确认并签发" : "模拟付款成功"}
      </button>
      {error ? (
        <p className="form-error" role="alert" style={{ marginTop: 8 }}>
          {error}
        </p>
      ) : null}
    </>
  );
}
