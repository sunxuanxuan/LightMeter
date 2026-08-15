"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";

export function CopyButton({
  value,
  label = "复制",
}: {
  value: string;
  label?: string;
}) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1_600);
  }

  return (
    <button className="button button--secondary" type="button" onClick={copy}>
      {copied ? <Check size={17} /> : <Copy size={17} />}
      {copied ? "已复制" : label}
    </button>
  );
}
