/// <reference lib="webworker" />
import { DemoFleet } from "../lib/demo-fleet";
import type { FromWorker, LiveConfig, Position, ToWorker, TrackPoint } from "../lib/types";

/**
 * Everything high-frequency happens here, off the main thread: generating (demo) or receiving (live) positions,
 * JSON parsing, and coalescing to the newest position per truck. The main thread gets one ready batch every
 * FLUSH_MS, so the map renders smoothly no matter how bursty the input is.
 */

const FLUSH_MS = 250;
const ctx = self as unknown as DedicatedWorkerGlobalScope;

const pending = new Map<string, Position>();
let inCount = 0;
let outCount = 0;
let lastStatsAt = Date.now();
let stats = { inPerSec: 0, outPerSec: 0 };

function post(msg: FromWorker): void {
  ctx.postMessage(msg);
}

function offer(p: Position): void {
  inCount++;
  const cur = pending.get(p.vehicleId);
  // Coalesce: keep only the newest per vehicle. (The main-thread store enforces the never-backwards rule too.)
  if (!cur || p.ts > cur.ts) {
    pending.set(p.vehicleId, p);
  }
}

setInterval(() => {
  const now = Date.now();
  if (now - lastStatsAt >= 1000) {
    const secs = (now - lastStatsAt) / 1000;
    stats = { inPerSec: Math.round(inCount / secs), outPerSec: Math.round(outCount / secs) };
    inCount = 0;
    outCount = 0;
    lastStatsAt = now;
  }
  if (pending.size === 0) {
    post({ type: "batch", items: [], stats });
    return;
  }
  const items = [...pending.values()];
  pending.clear();
  outCount += items.length;
  post({ type: "batch", items, stats });
}, FLUSH_MS);

// ------------------------------------------------------------------ demo mode

let demo: DemoFleet | null = null;

function startDemo(trucks: number, seed?: number): void {
  const now = Date.now();
  demo = new DemoFleet(trucks, now, seed);
  // Initial state: the newest pre-rolled fix of every truck, like a snapshot from the query API.
  post({ type: "snapshot", items: demo.snapshot() });
  post({ type: "status", state: "demo" });
  setInterval(() => {
    if (demo) {
      for (const p of demo.advance(Date.now())) {
        offer(p);
      }
    }
  }, 100);
}

// ------------------------------------------------------------------ live mode

let live: LiveConfig | null = null;
let token: string | null = null;
let backoffMs = 500;

async function fetchToken(cfg: LiveConfig): Promise<string> {
  // Demo environments issue dispatcher tokens from the query API; production would use OIDC + PKCE.
  const res = await fetch(`${cfg.queryUrl}/v1/dev/token`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ user: "dispatcher", fleets: cfg.fleets }),
  });
  if (!res.ok) {
    throw new Error(`token request failed: ${res.status}`);
  }
  const body = (await res.json()) as { token: string };
  return body.token;
}

async function loadSnapshot(cfg: LiveConfig, tok: string): Promise<void> {
  const all: Position[] = [];
  for (const fleet of cfg.fleets) {
    const res = await fetch(`${cfg.queryUrl}/v1/fleets/${encodeURIComponent(fleet)}/positions`, {
      headers: { Authorization: `Bearer ${tok}` },
    });
    if (!res.ok) {
      throw new Error(`snapshot ${fleet}: ${res.status}`);
    }
    const body = (await res.json()) as { positions: Omit<Position, "fleetId">[] };
    for (const p of body.positions) {
      all.push({ ...p, fleetId: fleet });
    }
  }
  post({ type: "snapshot", items: all });
}

function connect(): void {
  const cfg = live;
  if (!cfg) {
    return;
  }
  post({ type: "status", state: backoffMs > 500 ? "reconnecting" : "connecting" });
  void (async () => {
    try {
      token ??= await fetchToken(cfg);
      const ws = new WebSocket(`${cfg.gatewayUrl}?token=${encodeURIComponent(token)}`);
      ws.onopen = () => {
        // Subscribe first, then load the snapshot: updates that arrive in between are kept, and anything the
        // snapshot holds that is older than a live update is rejected by the never-backwards guard.
        for (const f of cfg.fleets) {
          ws.send(JSON.stringify({ type: "subscribe", fleetId: f }));
        }
        loadSnapshot(cfg, token!)
          .then(() => {
            backoffMs = 500;
            post({ type: "status", state: "live" });
          })
          .catch((e: unknown) => post({ type: "status", state: "error", detail: String(e) }));
      };
      ws.onmessage = (e: MessageEvent<string>) => {
        const msg = JSON.parse(e.data) as { type: string; items?: Position[]; detail?: string };
        if (msg.type === "positions" && msg.items) {
          for (const p of msg.items) {
            offer(p);
          }
        } else if (msg.type === "error") {
          post({ type: "status", state: "error", detail: msg.detail });
        }
      };
      ws.onclose = (e) => {
        if (e.code === 1008 || e.code === 4401) {
          token = null; // auth problem: fetch a fresh token next time
        }
        scheduleReconnect();
      };
      ws.onerror = () => ws.close();
    } catch (e) {
      token = null;
      post({ type: "status", state: "reconnecting", detail: String(e) });
      scheduleReconnect();
    }
  })();
}

function scheduleReconnect(): void {
  post({ type: "status", state: "reconnecting" });
  // Exponential backoff with full jitter, capped at 30 s: a restarted gateway is not stampeded by every browser.
  const delay = backoffMs / 2 + Math.random() * (backoffMs / 2);
  backoffMs = Math.min(30_000, backoffMs * 2);
  setTimeout(connect, delay);
}

async function liveTrack(vehicleId: string, fleetId: string, from: number, to: number): Promise<void> {
  if (!live || !token) {
    return;
  }
  const url = `${live.queryUrl}/v1/fleets/${encodeURIComponent(fleetId)}/vehicles/${encodeURIComponent(vehicleId)}/track`
    + `?from=${new Date(from).toISOString()}&to=${new Date(to).toISOString()}`;
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (res.ok) {
    const body = (await res.json()) as { points: TrackPoint[]; resolution: string };
    post({ type: "track", vehicleId, points: body.points, resolution: body.resolution });
  }
}

// ------------------------------------------------------------------ messages

ctx.onmessage = (e: MessageEvent<ToWorker>) => {
  const msg = e.data;
  switch (msg.type) {
    case "start-demo":
      startDemo(msg.trucks, msg.seed);
      break;
    case "start-live":
      live = msg.config;
      connect();
      break;
    case "track":
      if (demo) {
        post({ type: "track", vehicleId: msg.vehicleId, points: demo.track(msg.vehicleId, msg.from, msg.to), resolution: "raw" });
      } else {
        void liveTrack(msg.vehicleId, msg.fleetId, msg.from, msg.to);
      }
      break;
  }
};
