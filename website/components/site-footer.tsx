import { ShieldCheck, WifiOff } from "lucide-react";
import Link from "next/link";

export function SiteFooter() {
  return (
    <footer className="site-footer">
      <div className="site-footer__inner">
        <div className="footer-assurance">
          <span>
            <WifiOff size={15} aria-hidden="true" />
            App 完全离线
          </span>
          <span>
            <ShieldCheck size={15} aria-hidden="true" />
            设备绑定凭证
          </span>
        </div>
        <div className="footer-links">
          <Link href="/privacy">隐私说明</Link>
          <Link href="/terms">购买条款</Link>
          <span>© 2026 FilmLightMeter</span>
        </div>
      </div>
    </footer>
  );
}
