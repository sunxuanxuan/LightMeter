import {
  Camera,
  CircleDot,
  Contrast,
  Focus,
  KeyRound,
  ScanLine,
  ShieldCheck,
  Smartphone,
} from "lucide-react";
import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { PageHeader } from "@/components/page-header";

export const metadata: Metadata = {
  title: "使用说明",
};

const guideImagePrompt = encodeURIComponent(
  "Realistic editorial documentary photograph of a vintage 35mm film camera on a clean workbench beside a modern smartphone displaying a professional light meter interface, real camera and phone clearly visible, soft daylight, neutral gray and natural green surroundings, one orange camera strap as a strong compositional accent, no readable text, high detail, landscape composition",
);
const guideImage = `https://copilot-cn.bytedance.net/api/ide/v1/text_to_image?prompt=${guideImagePrompt}&image_size=landscape_16_9`;

const modes = [
  {
    icon: ScanLine,
    title: "平均测光",
    text: "读取整个取景范围，适合光线均匀的日常场景。",
  },
  {
    icon: Focus,
    title: "中央区域平均",
    text: "只读取画面中央区域，适合主体集中在中心的构图。",
  },
  {
    icon: Contrast,
    title: "中央重点",
    text: "强化画面中央主体，同时保留周边环境亮度。",
  },
  {
    icon: CircleDot,
    title: "点测光",
    text: "针对局部区域精确测量，适合高反差场景。",
  },
];

export default function GuidePage() {
  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Quick guide"
        title="从现场光线到可靠曝光"
        description="FilmLightMeter 提供一次性胶片预览与专业测光两种模式。根据手中的相机和拍摄需求，选择对应流程开始使用。"
        action={
          <Link className="button button--primary" href="/download">
            <Smartphone size={17} />
            获取最新版本
          </Link>
        }
      />

      <div className="guide-layout">
        <aside className="guide-toc" aria-label="本页目录">
          <p>本页目录</p>
          <nav>
            <a href="#disposable-preview">一次性胶片预览</a>
            <a href="#professional-mode">专业模式</a>
            <a href="#activation">离线激活</a>
            <a href="#privacy">隐私</a>
            <a href="#faq">常见问题</a>
          </nav>
        </aside>

        <div className="guide-content">
          <figure className="guide-visual">
            <Image
              src={guideImage}
              alt="胶片相机与运行测光应用的手机放置在工作台上"
              fill
              sizes="(max-width: 820px) 100vw, 820px"
              priority
              unoptimized
            />
            <figcaption className="guide-visual__caption">
              将手机与胶片相机朝向同一场景，再选择预览或专业测光流程。
            </figcaption>
          </figure>

          <section className="guide-section" id="quick-start">
            <div className="section-heading">
              <h2>快速开始</h2>
              <p>两种模式互不影响，分别保留上次使用的设置。</p>
            </div>

            <div className="mode-guide">
              <article className="mode-guide__section" id="disposable-preview">
                <div className="mode-guide__heading">
                  <span className="mode-guide__number">1</span>
                  <div>
                    <h3>一次性胶片预览模式</h3>
                    <p>
                      适合使用固定光圈、快门和 ISO
                      的一次性胶片相机，直接判断当前场景是否容易过曝或欠曝。
                    </p>
                  </div>
                  <Camera size={24} aria-hidden="true" />
                </div>
                <ol className="instruction-list">
                  <li>
                    <div>
                      <strong>选择一次性相机</strong>
                      <span>按品牌和型号选择预设，并核对固定参数与闪光灯提示。</span>
                    </div>
                  </li>
                  <li>
                    <div>
                      <strong>进入实时曝光预览</strong>
                      <span>将手机朝向拍摄场景，查看这台相机大致会拍出的明暗效果。</span>
                    </div>
                  </li>
                  <li>
                    <div>
                      <strong>查看风险与拍摄建议</strong>
                      <span>根据过曝、欠曝区域和文字建议，决定是否开启闪光灯或调整构图。</span>
                    </div>
                  </li>
                </ol>
              </article>

              <article className="mode-guide__section" id="professional-mode">
                <div className="mode-guide__heading">
                  <span className="mode-guide__number">2</span>
                  <div>
                    <h3>专业模式</h3>
                    <p>
                      适合可手动调整曝光的胶片相机，提供完整测光、曝光组合和画幅控制。
                    </p>
                  </div>
                  <ScanLine size={24} aria-hidden="true" />
                </div>
                <ol className="instruction-list">
                  <li>
                    <div>
                      <strong>设置 ISO、画幅与焦段</strong>
                      <span>ISO 按实际胶卷或曝光指数设置，焦段用于匹配取景范围。</span>
                    </div>
                  </li>
                  <li>
                    <div>
                      <strong>选择测光方式</strong>
                      <span>根据主体位置和场景反差，从下方四种方式中选择。</span>
                    </div>
                  </li>
                  <li>
                    <div>
                      <strong>读取曝光组合</strong>
                      <span>查看推荐光圈、快门及等效组合，并按拍摄意图调整。</span>
                    </div>
                  </li>
                  <li>
                    <div>
                      <strong>冻结画面分析明暗</strong>
                      <span>比较高光与阴影风险，确认曝光后再进行拍摄。</span>
                    </div>
                  </li>
                </ol>

                <div className="metering-intro">
                  <h4>专业模式中的测光方式</h4>
                  <p>测光方式只改变亮度读取范围，焦段与其他参数仍由你手动调整。</p>
                </div>
                <div className="feature-grid">
                  {modes.map((mode) => {
                    const Icon = mode.icon;
                    return (
                      <article className="feature-card" key={mode.title}>
                        <Icon size={24} strokeWidth={1.7} aria-hidden="true" />
                        <h5>{mode.title}</h5>
                        <p>{mode.text}</p>
                      </article>
                    );
                  })}
                </div>
              </article>
            </div>
          </section>

          <section className="guide-section" id="activation">
            <div className="section-heading">
              <h2>离线激活</h2>
              <p>App 不联网，官网只负责在付款后签发设备绑定凭证。</p>
            </div>
            <ol className="instruction-list">
              <li>
                <div>
                  <strong>复制 App 显示的设备 ID</strong>
                  <span>设备 ID 是 16 位十六进制字符。</span>
                </div>
              </li>
              <li>
                <div>
                  <strong>在官网获取激活凭证</strong>
                  <span>填写设备 ID 与购买邮箱，并确认单设备授权。</span>
                </div>
              </li>
              <li>
                <div>
                  <strong>复制完整凭证回到 App</strong>
                  <span>App 使用内置公钥离线验签，之后无需访问网络。</span>
                </div>
              </li>
            </ol>
            <div style={{ marginTop: 20 }}>
              <Link className="button button--primary" href="/activate">
                <KeyRound size={17} />
                获取激活码
              </Link>
            </div>
          </section>

          <section className="guide-section" id="privacy">
            <div className="section-heading">
              <h2>隐私与数据</h2>
            </div>
            <div className="notice">
              <ShieldCheck size={19} aria-hidden="true" />
              <span>
                App 不申请网络权限，不上传相机画面、测光结果或激活凭证。官网只保存完成购买、找回和售后所需的订单数据。
              </span>
            </div>
          </section>

          <section className="guide-section" id="faq">
            <div className="section-heading">
              <h2>常见问题</h2>
            </div>
            <div className="faq-list">
              <details>
                <summary>更换手机后原凭证还能使用吗？</summary>
                <p>
                  不能。凭证与设备 ID
                  绑定，需要提供原订单号和新设备 ID 申请重新签发。
                </p>
              </details>
              <details>
                <summary>为什么恢复出厂设置后需要重新激活？</summary>
                <p>
                  系统重置可能改变设备 ID 或清除本地凭证。请使用购买邮箱找回；设备
                  ID 变化时需要申请换机处理。
                </p>
              </details>
              <details>
                <summary>反射式测光为什么与手持表不同？</summary>
                <p>
                  手机测量的是场景反射光，结果会受主体颜色和高反差区域影响。需要精确入射光时，建议配合灰卡校准。
                </p>
              </details>
              <details>
                <summary>App 是否会上传照片？</summary>
                <p>
                  不会。相机帧只在本机内存中参与测光和预览，App
                  没有网络权限。
                </p>
              </details>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
