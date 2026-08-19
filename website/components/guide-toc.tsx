"use client";

import { useEffect, useRef, useState } from "react";

const sections = [
  { id: "disposable-preview", label: "一次性胶片预览" },
  { id: "professional-mode", label: "专业模式" },
  { id: "activation", label: "离线激活" },
  { id: "privacy", label: "隐私" },
  { id: "faq", label: "常见问题" },
];

export function GuideToc() {
  const [activeId, setActiveId] = useState(() => {
    if (typeof window === "undefined") {
      return sections[0].id;
    }

    const hashId = window.location.hash.slice(1);
    return sections.some((section) => section.id === hashId)
      ? hashId
      : sections[0].id;
  });
  const linkRefs = useRef(new Map<string, HTMLAnchorElement>());

  useEffect(() => {
    const sectionElements = sections
      .map(({ id }) => document.getElementById(id))
      .filter((element): element is HTMLElement => element !== null);

    const observer = new IntersectionObserver(
      (entries) => {
        const visibleEntry = entries
          .filter((entry) => entry.isIntersecting)
          .sort(
            (left, right) =>
              left.boundingClientRect.top - right.boundingClientRect.top,
          )[0];

        if (visibleEntry) {
          setActiveId(visibleEntry.target.id);
        }
      },
      {
        rootMargin: "-128px 0px -58%",
        threshold: 0,
      },
    );

    sectionElements.forEach((element) => observer.observe(element));
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const updateActiveSectionFromHash = () => {
      const hashId = window.location.hash.slice(1);
      if (sections.some((section) => section.id === hashId)) {
        setActiveId(hashId);
      }
    };

    window.addEventListener("hashchange", updateActiveSectionFromHash);
    updateActiveSectionFromHash();
    return () => {
      window.removeEventListener("hashchange", updateActiveSectionFromHash);
    };
  }, []);

  useEffect(() => {
    linkRefs.current.get(activeId)?.scrollIntoView({
      behavior: "smooth",
      block: "nearest",
      inline: "nearest",
    });
  }, [activeId]);

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
            ref={(element) => {
              if (element) {
                linkRefs.current.set(id, element);
              } else {
                linkRefs.current.delete(id);
              }
            }}
          >
            {label}
          </a>
        ))}
      </nav>
    </aside>
  );
}
