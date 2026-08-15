"use client";

import {
  Aperture,
  BookOpenText,
  Download,
  KeyRound,
  Menu,
  X,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";

const navigation = [
  { href: "/guide", label: "使用说明", icon: BookOpenText },
  { href: "/download", label: "下载", icon: Download },
  { href: "/activate", label: "获取激活码", icon: KeyRound, primary: true },
];

export function SiteHeader() {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);

  return (
    <header className="site-header">
      <div className="site-header__inner">
        <Link className="brand" href="/guide" onClick={() => setOpen(false)}>
          <span className="brand__mark" aria-hidden="true">
            <Aperture size={22} strokeWidth={1.8} />
          </span>
          <span>
            <strong>FilmLightMeter</strong>
            <small>胶片测光工具</small>
          </span>
        </Link>

        <button
          className="icon-button mobile-menu-button"
          type="button"
          aria-expanded={open}
          aria-controls="primary-navigation"
          aria-label={open ? "关闭导航" : "打开导航"}
          onClick={() => setOpen((value) => !value)}
        >
          {open ? <X size={20} /> : <Menu size={20} />}
        </button>

        <nav
          id="primary-navigation"
          className={`primary-nav ${open ? "primary-nav--open" : ""}`}
          aria-label="主导航"
        >
          {navigation.map((item) => {
            const active = pathname.startsWith(item.href);
            const Icon = item.icon;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={[
                  "nav-link",
                  active ? "nav-link--active" : "",
                  item.primary ? "nav-link--primary" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
                aria-current={active ? "page" : undefined}
                onClick={() => setOpen(false)}
              >
                <Icon size={16} aria-hidden="true" />
                {item.label}
              </Link>
            );
          })}
        </nav>
      </div>
    </header>
  );
}
