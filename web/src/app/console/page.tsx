"use client";

import dynamic from "next/dynamic";

// The console is browser-only (WebGL, Web Workers, WebSocket), so it is never pre-rendered.
const ConsoleApp = dynamic(() => import("@/components/ConsoleApp").then((m) => m.ConsoleApp), {
  ssr: false,
  loading: () => (
    <div className="grid h-dvh place-items-center bg-canvas text-sm text-muted">Loading dispatcher console…</div>
  ),
});

export default function ConsolePage() {
  return <ConsoleApp />;
}
