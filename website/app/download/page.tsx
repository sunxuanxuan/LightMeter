import { Download as DownloadIcon } from "lucide-react";
import type { Metadata } from "next";
import { connection } from "next/server";

import { AndroidDownload } from "@/components/android-download";
import { PageHeader } from "@/components/page-header";
import { getLatestRelease } from "@/lib/dal";

export const metadata: Metadata = {
  title: "下载",
};

export default async function DownloadPage() {
  await connection();
  const android = getLatestRelease("android");

  return (
    <div className="page-shell">
      <PageHeader
        eyebrow="Latest release"
        title="下载 FilmLightMeter"
        description="适用于 Android 手机。下载完成后按页面提示安装，首次打开时输入激活凭证即可使用。"
        action={
          <span className="badge badge--neutral">
            <DownloadIcon size={13} />
            安装包免费下载
          </span>
        }
      />
      <AndroidDownload release={android} />
    </div>
  );
}
