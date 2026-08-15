import type { Metadata } from "next";

import { PageHeader } from "@/components/page-header";

export const metadata: Metadata = {
  title: "购买条款",
};

export default function TermsPage() {
  return (
    <div className="page-shell page-shell--narrow">
      <PageHeader
        eyebrow="Purchase terms"
        title="单设备离线授权说明"
        description="付款前请核对设备 ID，并理解离线授权在换机、退款和远程吊销方面的边界。"
      />
      <article className="legal-content">
        <section>
          <h2>授权范围</h2>
          <p>
            每个订单默认签发一个设备绑定的永久使用凭证。凭证不能在不同设备之间复制使用。
          </p>
        </section>
        <section>
          <h2>换机与系统重置</h2>
          <p>
            更换设备或恢复出厂设置可能改变设备 ID。用户需要提供原订单信息，经人工核验后重新签发。
          </p>
        </section>
        <section>
          <h2>退款限制</h2>
          <p>
            App 完全离线，官网无法远程停用已经保存到设备的有效凭证。正式退款条件需要在支付渠道接入前由运营主体确认。
          </p>
        </section>
        <section>
          <h2>当前预览</h2>
          <p>
            本地网站中的付款和签发均为开发演示，不会产生真实交易，也不构成正式销售承诺。
          </p>
        </section>
      </article>
    </div>
  );
}
