import type { Metadata, Viewport } from "next";

import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "FilmLightMeter",
    template: "%s | FilmLightMeter",
  },
  description: "FilmLightMeter 使用说明、最新版本下载与离线激活凭证获取。",
};

export const viewport: Viewport = {
  colorScheme: "light dark",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f7f4" },
    { media: "(prefers-color-scheme: dark)", color: "#111311" },
  ],
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="zh-CN" data-scroll-behavior="smooth">
      <body>
        <SiteHeader />
        <main className="site-main">{children}</main>
        <SiteFooter />
      </body>
    </html>
  );
}
