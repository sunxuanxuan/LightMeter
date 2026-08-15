"use client";

import { LoaderCircle, Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { SyntheticEvent, useState } from "react";

export function RecoveryForm() {
  const router = useRouter();
  const [orderNo, setOrderNo] = useState("");
  const [email, setEmail] = useState("");
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setMessage(null);
    const response = await fetch("/api/orders/recover", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ orderNo, email }),
    });
    const result = (await response.json()) as {
      localResultUrl?: string | null;
    };
    if (result.localResultUrl) {
      router.push(result.localResultUrl);
      return;
    }
    setMessage(
      "请求已受理。正式环境会在订单匹配时向购买邮箱发送一次性找回链接。",
    );
    setLoading(false);
  }

  return (
    <form className="recovery-panel form-grid" onSubmit={submit}>
      <div className="field">
        <label htmlFor="recovery-order">订单号</label>
        <input
          id="recovery-order"
          value={orderNo}
          onChange={(event) => setOrderNo(event.target.value.toUpperCase())}
          placeholder="FLM-20260815-XXXXXXXX"
          autoComplete="off"
          required
        />
      </div>
      <div className="field">
        <label htmlFor="recovery-email">购买邮箱</label>
        <input
          id="recovery-email"
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="name@example.com"
          autoComplete="email"
          required
        />
      </div>
      {message ? (
        <div className="notice" role="status">
          <span>{message}</span>
        </div>
      ) : null}
      <button
        className="button button--primary"
        type="submit"
        disabled={loading}
      >
        {loading ? (
          <LoaderCircle className="spin" size={18} />
        ) : (
          <Search size={18} />
        )}
        {loading ? "正在核对" : "找回凭证"}
      </button>
      <small style={{ color: "var(--muted)" }}>
        本地预览会直接恢复匹配订单；生产环境始终返回相同提示，并通过邮箱交付链接。
      </small>
    </form>
  );
}
