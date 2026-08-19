"use client";

import { useEffect, useState } from "react";

const sections = [
  { id: "disposable-preview", label: "一次性胶片预览" },
  { id: "professional-mode", label: "专业模式" },
  { id: "activation", label: "离线激活" },
  { id: "privacy", label: "隐私" },
  { id: "faq", label: "常见问题" },
];

export function GuideToc() {
  const [activeId, setActiveId] = useState(sections[0].id);

  useEffect(() => {
    const sectionElements = sections
      .map(({ id }) => document.getElementById(id))
      .filter((element): element is HTMLElement => element !== null);

    const updateActiveSection = () => {
      const currentSection = [...sectionElements]
        .reverse()
        .find((element) => element.getBoundingClientRect().top <= 128);

      if (currentSection) {
        setActiveId(currentSection.id);
      } else {
        setActiveId(sections[0].id);
      }
    };

    window.addEventListener("scroll", updateActiveSection, { passive: true });
    window.addEventListener("resize", updateActiveSection);
    window.addEventListener("hashchange", updateActiveSection);
    updateActiveSection();

    return () => {
      window.removeEventListener("scroll", updateActiveSection);
      window.removeEventListener("resize", updateActiveSection);
      window.removeEventListener("hashchange", updateActiveSection);
    };
  }, []);

  return (
    <aside className="guide-toc" aria-label="本页目录">
      <p>本页目录</p>
      <nav>
        {sections.map(({ id, label }) => (
          <a
            aria-current={activeId === id ? "location" : undefined}
            className={activeId === id ? "guide-toc__link--active" : undefined}
            href={`#${id}`}
            key={id}
            onClick={() => setActiveId(id)}
          >
            {label}
          </a>
        ))}
      </nav>
    </aside>
  );
}
