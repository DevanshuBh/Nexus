"use client";

import type { FleetStore } from "@/lib/fleet-store";

interface Props {
  store: FleetStore;
  now: number;
  onSelect: (id: string) => void;
}

/** Operational alerts the dispatcher can act on: stale vehicles first, plus what the integrity guard caught. */
export function AlertsFeed({ store, now, onSelect }: Props) {
  const stale: { id: string; ageS: number }[] = [];
  for (const [id, v] of store.vehicles) {
    if (store.status(v, now) === "stale") {
      stale.push({ id, ageS: Math.round((now - v.arrivedAt) / 1000) });
    }
  }
  stale.sort((a, b) => b.ageS - a.ageS);

  return (
    <div className="min-h-0 flex-1 overflow-y-auto p-3 text-sm">
      <div className="mb-3 rounded-lg border border-line bg-surface-2 p-3">
        <p className="font-medium text-ink">Out-of-order pings rejected</p>
        <p className="mt-0.5 text-2xl font-semibold tabular-nums text-ink">{store.rejectedBackwards.toLocaleString()}</p>
        <p className="mt-1 text-xs text-muted">
          Late or replayed positions older than what the map already shows. Dropping them is why a truck never jumps backwards.
        </p>
      </div>
      <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
        Stale vehicles ({stale.length}) · no ping for 60 s+
      </h3>
      {stale.length === 0 ? (
        <p className="text-muted">Every vehicle has reported in the last minute.</p>
      ) : (
        <ul className="space-y-1">
          {stale.slice(0, 200).map((s) => (
            <li key={s.id}>
              <button
                type="button"
                onClick={() => onSelect(s.id)}
                className="flex w-full items-center justify-between rounded-md px-2 py-2 text-left hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-accent"
              >
                <span className="font-mono text-[13px] text-ink">{s.id}</span>
                <span className="tabular-nums text-muted">{s.ageS}s</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
