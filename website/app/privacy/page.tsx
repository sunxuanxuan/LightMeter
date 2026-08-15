import type { Metadata } from "next";

import { PageHeader } from "@/components/page-header";

export const metadata: Metadata = {
  title: "隐私说明",
};

export default function PrivacyPage() {
  return (
    <div className="page-shell page-shell--narrow">
      <PageHeader
        eyebrow="Privacy"
        title="只处理完成服务所需的数据"
        description="FilmLightMeter App 完全离线；官网仅处理下载、订单、凭证交付和售后所需信息。"
      />
      <article className="legal-content">
        <section>
          <h2>App 数据</h2>
          <p>
            App 不申请网络权限，不上传相机画面、测光结果、设置或激活凭证。相机帧只在设备本地内存中参与测光。
          </p>
        </section>
        <section>
          <h2>官网数据</h2>
          <p>
            购买时保存邮箱、设备 ID、订单状态和签发记录，用于交付、找回与换机核验。敏感字段在数据库中加密存储。
          </p>
        </section>
        <section>
          <h2>日志</h2>
          <p>
            服务日志不记录完整设备 ID、完整签名凭证、结果会话 token
            或支付敏感信息。
          </p>
        </section>
        <section>
          <h2>联系与删除</h2>
          <p>
            正式上线前将补充运营主体、客服邮箱、保存期限和数据删除申请方式。
          </p>
        </section>
      </article>
    </div>
  );
}
