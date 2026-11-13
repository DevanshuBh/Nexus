"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { FleetStore } from "@/lib/fleet-store";
import { GATEWAY_URL, LIVE_AVAILABLE, LIVE_FLEETS, QUERY_URL } from "@/lib/config";
import type { ConnectionState, FromWorker, ToWorker, TrackPoint, WorkerStats } from "@/lib/types";
import { FleetMap } from "./FleetMap";
import { VehicleList } from "./VehicleList";
import { VehiclePanel } from "./VehiclePanel";
import { AlertsFeed } from "./AlertsFeed";

const FLEET_SIZES = [500, 2000, 5000, 10000];
const PLAYBACK_SPEED = 30; // 30x real time

function readParams() {
  const sp = new URLSearchParams(window.location.search);
  const trucks = Number(sp.get("trucks"));
  return {
    mode: sp.get("mode") === "live" && LIVE_AVAILABLE ? ("live" as const) : ("demo" as const),
    trucks: FLEET_SIZES.includes(trucks) ? trucks : 2000,
    vehicle: sp.get("v"),
  };
}

function writeParams(update: Record<string, string | null>) {
  const sp = new URLSearchParams(window.location.search);
  for (const [k, v] of Object.entries(update)) {
    if (v === null) {
      sp.delete(k);
    } else {
      sp.set(k, v);
    }
  }
  const qs = sp.toString();
  window.history.replaceState(null, "", `${window.location.pathname}${qs ? `?${qs}` : ""}`);
}

const stateLabel: Record<ConnectionState, string> = {
  demo: "Demo · simulated in your browser",
  connecting: "Connecting…",
  live: "Live",
  reconnecting: "Reconnecting…",
  error: "Error",
};

export function ConsoleApp() {
  const store = useMemo(() => new FleetStore(), []);
  const workerRef = useRef<Worker | null>(null);
  const [params, setParams] = useState<ReturnType<typeof readParams> | null>(null);
  const [conn, setConn] = useState<{ state: ConnectionState; detail?: string }>({ state: "connecting" });
  const [stats, setStats] = useState<WorkerStats>({ inPerSec: 0, outPerSec: 0 });
  const [fps, setFps] = useState(0);
  const [now, setNow] = useState(0);
  const [tab, setTab] = useState<"vehicles" | "alerts">("vehicles");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [follow, setFollow] = useState(false);
  const [track, setTrack] = useState<TrackPoint[] | null>(null);
  const [playbackTs, setPlaybackTs] = useState<number | null>(null);
  const [playing, setPlaying] = useState(false);
  const [flyTo, setFlyTo] = useState<{ lon: number; lat: number; seq: number } | null>(null);
  const linkedVehicle = useRef<string | null>(null);

  // URL is the source of truth for mode, fleet size and the selected vehicle, so a link reopens the same view.
  useEffect(() => {
    const p = readParams();
    setParams(p);
    linkedVehicle.current = p.vehicle;
  }, []);

  useEffect(() => {
    if (!params) {
      return;
    }
    const worker = new Worker(new URL("../workers/fleet.worker.ts", import.meta.url), { type: "module" });
    workerRef.current = worker;
    worker.onmessage = (e: MessageEvent<FromWorker>) => {
      const msg = e.data;
      if (msg.type === "batch") {
        store.apply(msg.items, performance.now());
        setStats(msg.stats);
      } else if (msg.type === "snapshot") {
        store.apply(msg.items, performance.now());
      } else if (msg.type === "status") {
        setConn({ state: msg.state, detail: msg.detail });
      } else if (msg.type === "track") {
        setTrack(msg.points);
      }
    };
    const start: ToWorker = params.mode === "live"
      ? { type: "start-live", config: { queryUrl: QUERY_URL, gatewayUrl: GATEWAY_URL, fleets: LIVE_FLEETS } }
      : { type: "start-demo", trucks: params.trucks };
    worker.postMessage(start);
    return () => {
      worker.terminate();
      workerRef.current = null;
    };
  }, [params, store]);

  // Lists and panels refresh once per second; the map animates independently every frame.
  useEffect(() => {
    setNow(performance.now());
    const t = setInterval(() => setNow(performance.now()), 1000);
    return () => clearInterval(t);
  }, []);

  const requestTrack = useCallback((id: string) => {
    const v = store.vehicles.get(id);
    const to = Date.now();
    setTrack(null);
    setPlaybackTs(null);
    setPlaying(false);
    workerRef.current?.postMessage({ type: "track", vehicleId: id, fleetId: v?.current.fleetId ?? "", from: to - 600_000, to } satisfies ToWorker);
  }, [store]);

  const select = useCallback((id: string | null) => {
    setSelectedId(id);
    writeParams({ v: id });
    if (id) {
      requestTrack(id);
      const v = store.vehicles.get(id);
      if (v) {
        setFlyTo({ lon: v.current.lon, lat: v.current.lat, seq: Date.now() });
      }
    } else {
      setTrack(null);
      setFollow(false);
    }
  }, [requestTrack, store]);

  // A shared link with ?v= : select that vehicle once it has arrived from the snapshot.
  useEffect(() => {
    const id = linkedVehicle.current;
    if (id && store.vehicles.has(id) && workerRef.current) {
      linkedVehicle.current = null;
      select(id);
    }
  }, [now, select, store]);

  // Playback clock.
  useEffect(() => {
    if (!playing || !track || track.length < 2) {
      return;
    }
    const end = track[track.length - 1]!.ts;
    let last = performance.now();
    let raf = 0;
    const step = () => {
      const t = performance.now();
      setPlaybackTs((ts) => {
        const next = (ts ?? track[0]!.ts) + (t - last) * PLAYBACK_SPEED;
        if (next >= end) {
          setPlaying(false);
          return end;
        }
        return next;
      });
      last = t;
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [playing, track]);

  const counts = store.counts(now);
  const total = store.vehicles.size;

  return (
    <div className="flex h-dvh flex-col bg-canvas text-ink">
      <header className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line bg-surface px-4 py-2.5">
        <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight text-ink">
          <span aria-hidden className="grid size-7 place-items-center rounded-md bg-accent text-sm font-bold text-on-accent">N</span>
          NEXUS <span className="hidden font-normal text-muted sm:inline">dispatcher console</span>
        </Link>
        <div
          role="status"
          className={`flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium ${
            conn.state === "live" || conn.state === "demo" ? "border-moving/40 text-ink" : "border-idle/50 text-ink"
          }`}
          title={conn.detail}
        >
          <span aria-hidden className={`size-2 rounded-full ${conn.state === "live" || conn.state === "demo" ? "animate-pulse bg-moving" : "bg-idle"}`} />
          {stateLabel[conn.state]}
        </div>
        <div className="ml-auto flex items-center gap-2 text-xs">
          {params?.mode === "demo" && (
            <>
              <label htmlFor="fleet-size" className="text-muted">Simulated trucks</label>
              <select
                id="fleet-size"
                value={params.trucks}
                onChange={(e) => {
                  writeParams({ trucks: e.target.value, v: null });
                  window.location.reload();
                }}
                className="rounded-md border border-line bg-surface px-2 py-1 text-ink"
              >
                {FLEET_SIZES.map((n) => <option key={n} value={n}>{n.toLocaleString()}</option>)}
              </select>
            </>
          )}
          {LIVE_AVAILABLE && (
            <button
              type="button"
              onClick={() => {
                writeParams({ mode: params?.mode === "live" ? null : "live", v: null });
                window.location.reload();
              }}
              className="rounded-md border border-line px-2 py-1 text-ink hover:bg-surface-2"
            >
              Switch to {params?.mode === "live" ? "demo" : "live back end"}
            </button>
          )}
        </div>
      </header>

      <div className="flex min-h-0 flex-1 flex-col-reverse md:flex-row">
        <aside className="flex h-[42vh] min-h-0 flex-col border-t border-line bg-surface md:h-auto md:w-80 md:border-r md:border-t-0">
          <div role="tablist" aria-label="Sidebar" className="flex border-b border-line">
            {(["vehicles", "alerts"] as const).map((t) => (
              <button
                key={t}
                role="tab"
                type="button"
                aria-selected={tab === t}
                onClick={() => setTab(t)}
                className={`flex-1 px-3 py-2.5 text-sm font-medium capitalize ${tab === t ? "border-b-2 border-accent text-ink" : "text-muted hover:text-ink"}`}
              >
                {t === "alerts" ? `Alerts (${counts.stale})` : `Vehicles (${total.toLocaleString()})`}
              </button>
            ))}
          </div>
          {tab === "vehicles"
            ? <VehicleList store={store} now={now} selectedId={selectedId} onSelect={select} />
            : <AlertsFeed store={store} now={now} onSelect={(id) => { select(id); setTab("vehicles"); }} />}
        </aside>

        <main className="relative min-h-0 flex-1">
          <FleetMap
            store={store}
            selectedId={selectedId}
            follow={follow}
            track={track}
            playbackTs={playbackTs}
            onSelect={select}
            onFps={setFps}
            flyTo={flyTo}
          />
          <div className="pointer-events-none absolute left-3 top-3 flex flex-wrap gap-2">
            <Stat label="Moving" value={counts.moving} tone="moving" />
            <Stat label="Idle" value={counts.idle} tone="idle" />
            <Stat label="Stale" value={counts.stale} tone="stale" />
            <Stat label="Updates/s in" value={stats.inPerSec} wide />
            <Stat label="After coalescing" value={stats.outPerSec} wide />
            <Stat label="FPS" value={fps} wide />
          </div>
          {selectedId && (
            <div className="pointer-events-none absolute bottom-3 left-3 right-3 flex justify-end sm:bottom-auto sm:top-16 sm:left-auto">
              <VehiclePanel
                store={store}
                now={now}
                vehicleId={selectedId}
                follow={follow}
                onFollow={setFollow}
                onClose={() => select(null)}
                track={track}
                playbackTs={playbackTs}
                onPlaybackTs={setPlaybackTs}
                playing={playing}
                onPlaying={setPlaying}
              />
            </div>
          )}
          {total === 0 && (
            <div className="absolute inset-0 grid place-items-center">
              <p className="rounded-lg bg-surface px-4 py-3 text-sm text-muted shadow">
                {conn.state === "error" ? `Could not load the fleet: ${conn.detail ?? "unknown error"}` : "Loading fleet…"}
              </p>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}

/** `wide` tiles are hidden on phones so the map stays usable. */
function Stat({ label, value, tone, wide }: { label: string; value: number; tone?: "moving" | "idle" | "stale"; wide?: boolean }) {
  const dot = tone === "moving" ? "bg-moving" : tone === "idle" ? "bg-idle" : tone === "stale" ? "bg-stale" : null;
  return (
    <div className={`rounded-lg border border-line bg-surface/90 px-2.5 py-1.5 shadow-sm backdrop-blur ${wide ? "hidden sm:block" : ""}`}>
      <p className="flex items-center gap-1.5 text-[11px] text-muted">
        {dot && <span aria-hidden className={`size-2 rounded-full ${dot}`} />}
        {label}
      </p>
      <p className="text-sm font-semibold tabular-nums text-ink">{value.toLocaleString()}</p>
    </div>
  );
}
