import { ArrowLeft } from "lucide-react";
import type { Metadata } from "next";
import Link from "next/link";

import { PageHeader } from "@/components/page-header";
import { RecoveryForm } from "@/components/recovery-form";

export const metadata: Metadata = {
  title: "找回激活凭证",
};

export default function RecoverPage() {
  return (
    <div className="page-shell page-shell--narrow">
      <PageHeader
        eyebrow="Credential recovery"
        title="找回已购买凭证"
        description="输入订单号和购买邮箱。找回只返回原设备凭证，不会修改绑定设备。"
        action={
          <Link className="button button--secondary" href="/activate">
            <ArrowLeft size={16} />
            返回激活页
          </Link>
        }
      />
      <RecoveryForm />
    </div>
  );
}
