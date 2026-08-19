import { ArrowRight, Clock3, RotateCcw } from "lucide-react";
import type { Metadata } from "next";
import { cookies } from "next/headers";
import Link from "next/link";
import { connection } from "next/server";

import { ActivationForm } from "@/components/activation-form";
import { CancelOrderButton } from "@/components/cancel-order-button";
import { PageHeader } from "@/components/page-header";
import { formatPrice, siteConfig } from "@/lib/config";
import { getActiveOrderForSession, getCurrentPricing } from "@/lib/dal";

export const metadata: Metadata = {
  title: "获取激活码",
};

export default async function ActivatePage() {
  await connection();
  const pricing = getCurrentPricing();
  const cookieStore = await cookies();
  const sessionToken = cookieStore.get(siteConfig.resultCookieName)?.value;
  const activeOrder = sessionToken
    ? getActiveOrderForSession(sessionToken)
    : null;
  const activeOrderTitle =
    activeOrder?.status === "awaiting_confirmation"
      ? "你有一笔待确认订单"
      : activeOrder?.status === "issuing"
        ? "你的激活码正在生成"
        : "你有一笔待付款订单";

  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Offline license"
        title="为当前设备获取激活凭证"
      />
      {activeOrder ? (
        <section className="active-order-resume" aria-label="未完成订单">
          <Clock3 size={22} />
          <div className="active-order-resume__details">
            <strong>{activeOrderTitle}</strong>
            <span>
              {activeOrder.orderNo} ·{" "}
              {formatPrice(activeOrder.amountMinor, activeOrder.currency)}
            </span>
          </div>
          <div className="active-order-resume__actions">
            {activeOrder.status === "pending" ? (
              <CancelOrderButton orderNo={activeOrder.orderNo} />
            ) : null}
            <Link
              className="button button--primary"
              href={`/activate/result/${encodeURIComponent(activeOrder.orderNo)}`}
            >
              继续完成
              <ArrowRight size={16} />
            </Link>
          </div>
        </section>
      ) : (
        <ActivationForm
          listPriceMinor={pricing.priceMinor}
          currency={pricing.currency}
        />
      )}
      <div style={{ marginTop: 26 }}>
        <Link className="button button--secondary" href="/activate/recover">
          <RotateCcw size={16} />
          找回已购买凭证
        </Link>
      </div>
    </div>
  );
}
