"use client";

import { KeyRound, LoaderCircle, Smartphone } from "lucide-react";
import { useRouter } from "next/navigation";
import { SyntheticEvent, useRef, useState } from "react";

import { formatPrice, siteConfig } from "@/lib/config";
import { formatDeviceId, normalizeDeviceId } from "@/lib/validation";

export function ActivationForm() {
  const router = useRouter();
  const idempotencyKey = useRef<string | null>(null);
  const [deviceID, setDeviceID] = useState("");
  const [email, setEmail] = useState("");
  const [acceptedTerms, setAcceptedTerms] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    idempotencyKey.current ??= crypto.randomUUID();

    try {
      const response = await fetch("/api/orders", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": idempotencyKey.current,
        },
        body: JSON.stringify({
          platform: "android",
          deviceID,
          email,
          acceptedTerms,
        }),
      });
      const result = (await response.json()) as {
        resultUrl?: string;
        errorCode?: string;
        fieldErrors?: Record<string, string[]>;
      };
      if (!response.ok || !result.resultUrl) {
        const fieldMessage = result.fieldErrors
          ? Object.values(result.fieldErrors).flat()[0]
          : null;
        const paymentMessage =
          result.errorCode === "PAYMENT_NOT_CONFIGURED"
            ? "支付宝付款暂未开放"
            : result.errorCode === "PAYMENT_CAPACITY_REACHED"
              ? "当前付款订单较多，请稍后重试"
              : result.errorCode === "ORDER_RATE_LIMITED"
                ? "订单创建过于频繁，请稍后重试"
              : result.errorCode === "PAYMENT_CREATION_FAILED"
                ? "付款码生成失败，请稍后重试"
                : null;
        throw new Error(
          fieldMessage ?? paymentMessage ?? "订单创建失败，请检查输入后重试",
        );
      }
      router.push(result.resultUrl);
    } catch (submissionError) {
      setError(
        submissionError instanceof Error
          ? submissionError.message
          : "订单创建失败，请稍后重试",
      );
      setSubmitting(false);
    }
  }

  function updateDeviceID(value: string) {
    const normalized = normalizeDeviceId(value).replace(/[^0-9A-F]/g, "");
    setDeviceID(normalized.slice(0, 16));
  }

  return (
    <div className="activation-layout">
      <form className="activation-panel" onSubmit={submit}>
        <div className="step-indicator" aria-label="购买步骤">
          <span>1. 设备信息</span>
          <span>2. 确认付款</span>
          <span>3. 复制凭证</span>
        </div>

        <div className="form-grid">
          <div className="field">
            <span className="field-label">设备平台</span>
            <div className="platform-readonly">
              <Smartphone size={17} />
              <span>Android</span>
            </div>
          </div>

          <div className="field">
            <label htmlFor="device-id">设备 ID</label>
            <input
              id="device-id"
              className="device-input"
              value={formatDeviceId(deviceID)}
              onChange={(event) => updateDeviceID(event.target.value)}
              placeholder="A1B2 C3D4 E5F6 A7B8"
              inputMode="text"
              autoComplete="off"
              required
            />
            <small>在 App 激活页点击复制设备 ID，然后粘贴到这里。</small>
          </div>

          <div className="field">
            <label htmlFor="buyer-email">购买邮箱</label>
            <input
              id="buyer-email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="name@example.com"
              autoComplete="email"
              required
            />
            <small>用于订单确认、凭证找回和换机核验。</small>
          </div>

          <label className="checkbox-row">
            <input
              type="checkbox"
              checked={acceptedTerms}
              onChange={(event) => setAcceptedTerms(event.target.checked)}
              required
            />
            <span>
              我确认设备 ID 无误，并了解凭证仅适用于当前设备；换机或恢复出厂设置后需要申请重新签发。
            </span>
          </label>

          {error ? (
            <p className="form-error" role="alert">
              {error}
            </p>
          ) : null}

          <button
            className="button button--primary button--full"
            type="submit"
            disabled={submitting}
          >
            {submitting ? (
              <LoaderCircle size={18} className="spin" />
            ) : (
              <KeyRound size={18} />
            )}
            {submitting ? "正在生成付款码" : "生成支付宝付款码"}
          </button>
        </div>
      </form>

      <aside className="order-aside">
        <div className="order-summary">
          <h2>订单摘要</h2>
          <dl>
            <div>
              <dt>产品</dt>
              <dd>FilmLightMeter</dd>
            </div>
            <div>
              <dt>授权</dt>
              <dd>单设备永久授权</dd>
            </div>
            <div>
              <dt>设备</dt>
              <dd>{deviceID ? `尾号 ${deviceID.slice(-4)}` : "待填写"}</dd>
            </div>
            <div className="order-summary__price">
              <dt>价格</dt>
              <dd>{formatPrice(siteConfig.priceMinor, siteConfig.currency)}</dd>
            </div>
          </dl>
        </div>
      </aside>
    </div>
  );
}
