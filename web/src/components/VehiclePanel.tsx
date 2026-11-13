"use client";

import type { FleetStore } from "@/lib/fleet-store";
import type { TrackPoint } from "@/lib/types";
import { pointOnTrack } from "./FleetMap";
import { StatusDot } from "./StatusDot";

interface Props {
  store: FleetStore;
  now: number;
  vehicleId: string;
  follow: boolean;
  onFollow: (f: boolean) => void;
  onClose: () => void;
  track: TrackPoint[] | null;
  playbackTs: number | null;
  onPlaybackTs: (ts: number | null) => void;
  playing: boolean;
  onPlaying: (p: boolean) => void;
}

const fmtTime = (ts: number) => new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });

export function VehiclePanel(p: Props) {
  const v = p.store.vehicles.get(p.vehicleId);
  if (!v) {
    return null;
  }
  const status = p.store.status(v, p.now);
  const ageS = Math.max(0, Math.round((p.now - v.arrivedAt) / 1000));
  const t0 = p.track?.[0]?.ts;
  const t1 = p.track?.[p.track.length - 1]?.ts;
  const at = p.track && p.playbackTs !== null ? pointOnTrack(p.track, p.playbackTs) : null;

  return (
    <section
      aria-label={`Vehicle ${p.vehicleId}`}
      className="pointer-events-auto w-full rounded-xl border border-line bg-surface/95 p-4 shadow-lg backdrop-blur sm:w-80"
    >
      <header className="mb-3 flex items-start justify-between gap-2">
        <div>
          <h2 className="font-mono text-sm font-semibold text-ink">{p.vehicleId}</h2>
          <p className="mt-0.5 flex items-center gap-1.5 text-xs capitalize text-muted">
            <StatusDot status={status} /> {status} · fleet {v.current.fleetId}
          </p>
        </div>
        <button type="button" onClick={p.onClose} className="rounded-md px-2 py-1 text-sm text-muted hover:bg-surface-2 hover:text-ink" aria-label="Close vehicle panel">
          ✕
        </button>
      </header>

      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
        <div><dt className="text-xs text-muted">Speed</dt><dd className="tabular-nums text-ink">{Math.round(v.current.speedKph)} km/h</dd></div>
        <div><dt className="text-xs text-muted">Heading</dt><dd className="tabular-nums text-ink">{Math.round(v.current.heading)}°</dd></div>
        <div><dt className="text-xs text-muted">Last ping</dt><dd className="tabular-nums text-ink">{ageS}s ago</dd></div>
        <div><dt className="text-xs text-muted">Device time</dt><dd className="tabular-nums text-ink">{fmtTime(v.current.ts)}</dd></div>
        <div className="col-span-2"><dt className="text-xs text-muted">Position</dt><dd className="font-mono text-xs text-ink">{v.current.lat.toFixed(5)}, {v.current.lon.toFixed(5)}</dd></div>
      </dl>

      <label className="mt-3 flex items-center gap-2 text-sm text-ink">
        <input type="checkbox" checked={p.follow} onChange={(e) => p.onFollow(e.target.checked)} className="size-4 accent-[var(--color-accent)]" />
        Follow on map
      </label>

      <div className="mt-4 border-t border-line pt-3">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-muted">Route playback · last 10 min</h3>
        {!p.track ? (
          <p className="mt-2 text-sm text-muted">Loading route…</p>
        ) : p.track.length < 2 || t0 === undefined || t1 === undefined ? (
          <p className="mt-2 text-sm text-muted">No history in this window.</p>
        ) : (
          <div className="mt-2 space-y-2">
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => {
                  if (p.playbackTs === null || p.playbackTs >= t1) {
                    p.onPlaybackTs(t0);
                  }
                  p.onPlaying(!p.playing);
                }}
                className="rounded-md bg-accent px-3 py-1.5 text-xs font-semibold text-on-accent hover:opacity-90"
              >
                {p.playing ? "Pause" : "Play"}
              </button>
              <label className="sr-only" htmlFor="playback">Playback time</label>
              <input
                id="playback"
                type="range"
                min={t0}
                max={t1}
                step={1000}
                value={p.playbackTs ?? t1}
                onChange={(e) => {
                  p.onPlaying(false);
                  p.onPlaybackTs(Number(e.target.value));
                }}
                className="flex-1 accent-[var(--color-accent)]"
              />
            </div>
            <p className="text-xs tabular-nums text-muted">
              {at ? `${fmtTime(at.ts)} · ${Math.round(at.speedKph)} km/h` : `${p.track.length} points`}
            </p>
          </div>
        )}
      </div>
    </section>
  );
}
