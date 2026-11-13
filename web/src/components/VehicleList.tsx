"use client";

import { useMemo, useState } from "react";
import type { FleetStore } from "@/lib/fleet-store";
import type { VehicleStatus } from "@/lib/types";
import { StatusDot } from "./StatusDot";

const ROW = 44;
const OVERSCAN = 8;

interface Props {
  store: FleetStore;
  now: number;
  selectedId: string | null;
  onSelect: (id: string) => void;
}

/**
 * Searchable, filterable list of every truck: the accessible equivalent of the map. Windowed by hand (only visible
 * rows are in the DOM), so 10,000 vehicles cost the same as 20.
 */
export function VehicleList({ store, now, selectedId, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<VehicleStatus | "all">("all");
  const [scrollTop, setScrollTop] = useState(0);
  const [height, setHeight] = useState(480);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    const out: { id: string; status: VehicleStatus; speed: number; ageS: number }[] = [];
    for (const [id, v] of store.vehicles) {
      if (q && !id.toLowerCase().includes(q)) {
        continue;
      }
      const status = store.status(v, now);
      if (filter !== "all" && status !== filter) {
        continue;
      }
      out.push({ id, status, speed: v.current.speedKph, ageS: Math.max(0, Math.round((now - v.arrivedAt) / 1000)) });
    }
    out.sort((a, b) => a.id.localeCompare(b.id));
    return out;
    // `now` ticks once per second, which is when the list refreshes.
  }, [store, now, query, filter]);

  const first = Math.max(0, Math.floor(scrollTop / ROW) - OVERSCAN);
  const last = Math.min(rows.length, Math.ceil((scrollTop + height) / ROW) + OVERSCAN);

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="space-y-2 border-b border-line p-3">
        <label className="sr-only" htmlFor="vehicle-search">Search vehicles</label>
        <input
          id="vehicle-search"
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search vehicle id…"
          className="w-full rounded-md border border-line bg-surface px-3 py-2 text-sm text-ink placeholder:text-muted focus:outline-2 focus:outline-accent"
        />
        <div className="flex gap-1" role="group" aria-label="Filter by status">
          {(["all", "moving", "idle", "stale"] as const).map((f) => (
            <button
              key={f}
              type="button"
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
              className={`rounded-md px-2.5 py-1 text-xs font-medium capitalize transition-colors ${
                filter === f ? "bg-accent text-on-accent" : "text-muted hover:bg-surface-2 hover:text-ink"
              }`}
            >
              {f}
            </button>
          ))}
        </div>
        <p className="text-xs text-muted" aria-live="polite">{rows.length.toLocaleString()} vehicles</p>
      </div>
      <div
        className="min-h-0 flex-1 overflow-y-auto"
        onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}
        ref={(el) => {
          if (el && el.clientHeight !== height && el.clientHeight > 0) {
            setHeight(el.clientHeight);
          }
        }}
      >
        <ul style={{ height: rows.length * ROW, position: "relative" }} aria-label="Vehicles">
          {rows.slice(first, last).map((r, i) => (
            <li key={r.id} style={{ position: "absolute", top: (first + i) * ROW, left: 0, right: 0, height: ROW }}>
              <button
                type="button"
                onClick={() => onSelect(r.id)}
                aria-current={selectedId === r.id ? "true" : undefined}
                className={`flex h-full w-full items-center gap-3 px-3 text-left text-sm transition-colors focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent ${
                  selectedId === r.id ? "bg-accent-soft" : "hover:bg-surface-2"
                }`}
              >
                <StatusDot status={r.status} />
                <span className="flex-1 truncate font-mono text-[13px] text-ink">{r.id}</span>
                <span className="w-16 text-right tabular-nums text-muted">
                  {r.status === "stale" ? `${r.ageS}s ago` : `${Math.round(r.speed)} km/h`}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
