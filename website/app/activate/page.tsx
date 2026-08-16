import { RotateCcw } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";
import { connection } from "next/server";

import { ActivationForm } from "@/components/activation-form";
import { PageHeader } from "@/components/page-header";
import { getCurrentPricing } from "@/lib/dal";

export const metadata: Metadata = {
  title: "获取激活码",
};

export default async function ActivatePage() {
  await connection();
  const pricing = getCurrentPricing();

  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Offline license"
        title="为当前设备获取激活凭证"
      />
      <ActivationForm
        listPriceMinor={pricing.priceMinor}
        currency={pricing.currency}
      />
      <div style={{ marginTop: 26 }}>
        <Link className="button button--secondary" href="/activate/recover">
          <RotateCcw size={16} />
          找回已购买凭证
        </Link>
      </div>
    </div>
  );
}
