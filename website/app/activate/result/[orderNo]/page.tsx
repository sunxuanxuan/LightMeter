import {
    ArrowLeft,
    Clock3,
    KeyRound,
    LockKeyhole,
    ScanLine,
    ShieldCheck,
} from "lucide-react";
import type { Metadata } from "next";
import { cookies } from "next/headers";
import Image from "next/image";
import Link from "next/link";

import { CopyButton } from "@/components/copy-button";
import { PageHeader } from "@/components/page-header";
import { PaymentStatusPoller } from "@/components/payment-status-poller";
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
  const waitingForPayment = order.status === "pending";

  return (
    <div className="page-shell">
      <PaymentStatusPoller
        active={waitingForPayment}
        orderNo={order.orderNo}
      />
      <PageHeader
        eyebrow="Order result"
        title={fulfilled ? "激活凭证已签发" : "请使用支付宝扫码付款"}
        description={
          fulfilled
            ? "复制完整凭证回到 App。凭证只属于当前设备，不需要 App 联网。"
            : "付款成功后页面会自动更新，请不要关闭当前页面。"
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
              <h2>{fulfilled ? "激活凭证已生成" : "等待付款"}</h2>
              <p>
                {fulfilled
                  ? `签名密钥：${order.signingKeyId}`
                  : `订单金额：${formatPrice(order.amountMinor, order.currency)}`}
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
            <div className="payment-box">
              {order.paymentQrCode ? (
                <Image
                  className="payment-qr"
                  src={`/api/orders/${encodeURIComponent(order.orderNo)}/payment-qr`}
                  alt="支付宝付款二维码"
                  width={320}
                  height={320}
                  unoptimized
                  priority
                />
              ) : (
                <div className="payment-qr payment-qr--empty">
                  <Clock3 size={28} />
                  <span>正在准备付款码</span>
                </div>
              )}
              <div className="payment-instruction">
                <ScanLine size={20} />
                <div>
                  <strong>打开支付宝扫一扫</strong>
                  <span>付款完成后，请留在此页面等待结果。</span>
                </div>
              </div>
            </div>
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

        {fulfilled ? (
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
        ) : (
          <aside className="next-steps">
            <h2>付款提示</h2>
            <ol>
              <li>使用支付宝扫描页面中的付款码。</li>
              <li>确认金额为 ¥9.90 后完成付款。</li>
              <li>付款后无需手动操作，等待页面自动更新。</li>
            </ol>
            <p className="aside-note">付款码将在订单创建 30 分钟后失效。</p>
          </aside>
        )}
      </div>
    </div>
  );
}
