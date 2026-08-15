import {
  ArrowLeft,
  Clock3,
  KeyRound,
  LockKeyhole,
  ReceiptText,
  ShieldCheck,
} from "lucide-react";
import type { Metadata } from "next";
import { cookies } from "next/headers";
import Link from "next/link";

import { CopyButton } from "@/components/copy-button";
import { PageHeader } from "@/components/page-header";
import { SimulatePaymentButton } from "@/components/simulate-payment-button";
import { formatPrice, siteConfig } from "@/lib/config";
import { getOrder } from "@/lib/dal";
import { formatDeviceId } from "@/lib/validation";

export const metadata: Metadata = {
  title: "订单结果",
};

function formattedTime(value: string | number | null): string {
  if (!value) return "等待确认";
  const date = typeof value === "number" ? new Date(value * 1_000) : new Date(value);
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

export default async function ActivationResultPage({
  params,
}: {
  params: Promise<{ orderNo: string }>;
}) {
  const { orderNo } = await params;
  const cookieStore = await cookies();
  const sessionToken = cookieStore.get(siteConfig.resultCookieName)?.value;
  const order = sessionToken ? getOrder(orderNo, sessionToken) : null;

  if (!order) {
    return (
      <div className="page-shell page-shell--narrow">
        <PageHeader
          eyebrow="Order access"
          title="订单访问已失效"
          description="结果页需要当前浏览器的安全会话。可以使用订单号和购买邮箱重新找回。"
        />
        <div className="recovery-panel">
          <LockKeyhole size={28} />
          <p style={{ margin: "12px 0 20px", color: "var(--muted)" }}>
            订单号本身不能用于查看凭证，避免他人猜测订单后获得设备授权。
          </p>
          <Link className="button button--primary" href="/activate/recover">
            找回凭证
          </Link>
        </div>
      </div>
    );
  }

  const fulfilled = order.status === "fulfilled" && order.credential;

  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Order result"
        title={fulfilled ? "激活凭证已签发" : "订单等待付款确认"}
        description={
          fulfilled
            ? "复制完整凭证回到 App。凭证只属于当前设备，不需要 App 联网。"
            : "当前是本地演示订单。点击模拟付款后，服务端会记录支付事件并签发开发凭证。"
        }
        action={
          <Link className="button button--secondary" href="/activate">
            <ArrowLeft size={16} />
            返回
          </Link>
        }
      />

      <div className="result-layout">
        <section className="activation-panel">
          <div className="status-line">
            <span className="status-icon">
              {fulfilled ? <ShieldCheck size={21} /> : <Clock3 size={21} />}
            </span>
            <div>
              <h2>{fulfilled ? "签名验证材料已生成" : "等待支付服务回调"}</h2>
              <p>
                {fulfilled
                  ? `签名密钥：${order.signingKeyId}`
                  : "浏览器跳转不会直接改变付款状态"}
              </p>
            </div>
          </div>

          {fulfilled && order.credential ? (
            <>
              {order.isDevelopmentCredential ? (
                <div className="notice notice--warning" style={{ marginBottom: 16 }}>
                  <KeyRound size={18} />
                  <span>
                    这是本地开发密钥签发的演示凭证，生产 App
                    不会接受。配置正式私钥后才可签发真实凭证。
                  </span>
                </div>
              ) : null}
              <div className="credential-box">
                <pre>{order.credential}</pre>
              </div>
              <div className="credential-actions">
                <CopyButton value={order.credential} label="复制完整凭证" />
              </div>
            </>
          ) : (
            <>
              <div className="notice notice--warning" style={{ marginBottom: 18 }}>
                <ReceiptText size={18} />
                <span>
                  本地预览不会连接真实支付平台，也不会产生扣款。此按钮模拟已经验签的支付服务端回调。
                </span>
              </div>
              <SimulatePaymentButton orderNo={order.orderNo} />
            </>
          )}

          <dl className="result-details">
            <div>
              <dt>订单号</dt>
              <dd>{order.orderNo}</dd>
            </div>
            <div>
              <dt>设备 ID</dt>
              <dd>{formatDeviceId(order.deviceID)}</dd>
            </div>
            <div>
              <dt>平台</dt>
              <dd>Android</dd>
            </div>
            <div>
              <dt>金额</dt>
              <dd>{formatPrice(order.amountMinor, order.currency)}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{formattedTime(order.createdAt)}</dd>
            </div>
            <div>
              <dt>签发时间</dt>
              <dd>{formattedTime(order.issuedAt)}</dd>
            </div>
          </dl>
        </section>

        <aside className="next-steps">
          <h2>下一步</h2>
          <ol>
            <li>复制完整签名凭证，不要删改字符。</li>
            <li>回到 FilmLightMeter 激活页。</li>
            <li>粘贴凭证并点击“激活”。</li>
            <li>App 离线验签成功后即可长期使用。</li>
          </ol>
          <p className="aside-note">
            更换设备后设备 ID 会变化，需要提供订单号申请重新签发。
          </p>
        </aside>
      </div>
    </div>
  );
}
