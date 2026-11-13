import type { NextConfig } from "next";

// Static export: the console is a client-side app, so it hosts on any static host (Vercel, Cloudflare Pages, GitHub Pages).
const basePath = process.env.NEXT_PUBLIC_BASE_PATH || "";

const config: NextConfig = {
  output: "export",
  basePath,
  images: { unoptimized: true },
  trailingSlash: true,
  reactStrictMode: true,
};

export default config;
