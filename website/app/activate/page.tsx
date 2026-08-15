import { RotateCcw } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";

import { ActivationForm } from "@/components/activation-form";
import { PageHeader } from "@/components/page-header";

export const metadata: Metadata = {
  title: "获取激活码",
};

export default function ActivatePage() {
  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Offline license"
        title="为当前设备获取激活凭证"
      />
      <ActivationForm />
      <div style={{ marginTop: 26 }}>
        <Link className="button button--secondary" href="/activate/recover">
          <RotateCcw size={16} />
          找回已购买凭证
        </Link>
      </div>
    </div>
  );
}
