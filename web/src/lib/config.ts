/** Build-time configuration (NEXT_PUBLIC_* is inlined by Next.js). */
export const BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
export const REPO_URL = process.env.NEXT_PUBLIC_REPO_URL ?? "https://github.com/DevanshuBh/Nexus";

/** Set both to point the console at a running back end (see README: "Live mode"). */
export const QUERY_URL = process.env.NEXT_PUBLIC_QUERY_URL ?? "";
export const GATEWAY_URL = process.env.NEXT_PUBLIC_GATEWAY_URL ?? "";
export const LIVE_FLEETS = (process.env.NEXT_PUBLIC_FLEETS ?? "f01,f02").split(",").map((s) => s.trim()).filter(Boolean);

export const LIVE_AVAILABLE = QUERY_URL !== "" && GATEWAY_URL !== "";

export const MAP_STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron";
export const MAP_STYLE_DARK = "https://tiles.openfreemap.org/styles/dark";
export const MAP_CENTER: [number, number] = [77.5946, 12.9716]; // Bengaluru, lon/lat (GeoJSON order)
