import {
  CheckCircle2,
  Download,
  FileKey,
  KeyRound,
  Settings,
  Smartphone,
} from "lucide-react";

import type { ReleaseDTO } from "@/lib/dal";

function fileSize(bytes: number | null): string {
  if (!bytes) return "待发布";
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formattedDate(value: string | null): string {
  if (!value) return "待发布";
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium" }).format(
    new Date(value),
  );
}

export function AndroidDownload({
  release,
}: {
  release: ReleaseDTO | null;
}) {
  const available = release?.available ?? false;

  return (
    <div className="download-area">
      <section className="download-panel">
        <div className="download-panel__main">
          <div className="download-panel__heading">
            <h2>Android 安装包</h2>
            <span className={`badge ${available ? "" : "badge--warning"}`}>
              {available ? (
                <CheckCircle2 size={13} />
              ) : (
                <FileKey size={13} />
              )}
              {available
                ? release?.channel === "preview"
                  ? "本地预览"
                  : "已发布"
                : "等待发布"}
            </span>
          </div>
          <p className="release-summary">
            {release?.releaseNotes ?? "尚未检测到本地 Android 构建。"}
          </p>

          <div className="release-actions">
            {available ? (
              <a className="button button--primary" href="/api/download/android">
                <Download size={17} />
                下载 Android 版
              </a>
            ) : (
              <button className="button button--primary" type="button" disabled>
                <Download size={17} />
                Android 构建待发布
              </button>
            )}
          </div>
        </div>

        <dl className="release-meta">
          <div>
            <dt>版本</dt>
            <dd>{release?.versionName ?? "待发布"}</dd>
          </div>
          <div>
            <dt>系统要求</dt>
            <dd>{release?.minimumOS ?? "待确认"}</dd>
          </div>
          <div>
            <dt>发布日期</dt>
            <dd>{formattedDate(release?.publishedAt ?? null)}</dd>
          </div>
          <div>
            <dt>文件大小</dt>
            <dd>{fileSize(release?.fileSize ?? null)}</dd>
          </div>
        </dl>
      </section>

      <div className="install-notes">
        <div className="install-note">
          <Download size={21} aria-hidden="true" />
          <strong>1. 下载安装包</strong>
          <span>点击上方按钮，等待下载完成。</span>
        </div>
        <div className="install-note">
          <Settings size={21} aria-hidden="true" />
          <strong>2. 允许安装</strong>
          <span>如系统询问，请允许浏览器安装此应用。</span>
        </div>
        <div className="install-note">
          <KeyRound size={21} aria-hidden="true" />
          <strong>3. 完成激活</strong>
          <span>打开 App，按提示输入购买后获得的激活凭证。</span>
        </div>
      </div>
      <div className="notice" style={{ marginTop: 18 }}>
        <Smartphone size={18} aria-hidden="true" />
        <span>
          安装时若看到“未知来源应用”提示，这是 Android
          安装官网版本时的正常提醒，请确认文件来自本页面后继续。
        </span>
      </div>
    </div>
  );
}
