import type { Position, VehicleStatus } from "./types";

/** A vehicle is "stale" when nothing has arrived for this long (it may be offline or out of coverage). */
export const STALE_AFTER_MS = 60_000;
/** Below this speed a vehicle counts as idle. */
export const IDLE_BELOW_KPH = 2;
/** Upper bound for the glide between two positions, so a long gap does not animate for minutes. */
const MAX_GLIDE_MS = 4_000;

export interface VehicleState {
  current: Position;
  /** where the vehicle was drawn when `current` arrived: the glide starts here */
  from: { lat: number; lon: number };
  /** local clock (performance.now-like ms) when `current` arrived */
  arrivedAt: number;
  glideMs: number;
}

/**
 * The console's model of the fleet. Mutable and outside React on purpose: thousands of updates per second must not
 * cause thousands of re-renders. Views read it on animation frames, or subscribe to a throttled version counter.
 *
 * Invariant: a vehicle never moves backwards in time. An update older than (or equal to) what we hold is dropped and
 * counted, whether it comes from a replay, a reconnect snapshot, or a stale message from another gateway node.
 */
export class FleetStore {
  readonly vehicles = new Map<string, VehicleState>();
  rejectedBackwards = 0;
  version = 0;
  private listeners = new Set<() => void>();

  /** @returns number of updates applied */
  apply(items: readonly Position[], now: number): number {
    let applied = 0;
    for (const p of items) {
      const prev = this.vehicles.get(p.vehicleId);
      if (prev && p.ts <= prev.current.ts) {
        this.rejectedBackwards++;
        continue;
      }
      const from = prev ? this.positionAt(prev, now) : { lat: p.lat, lon: p.lon };
      const glideMs = prev ? Math.min(MAX_GLIDE_MS, Math.max(0, p.ts - prev.current.ts)) : 0;
      this.vehicles.set(p.vehicleId, { current: p, from, arrivedAt: now, glideMs });
      applied++;
    }
    if (applied > 0) {
      this.version++;
    }
    return applied;
  }

  /** Interpolated position for drawing: glides from where it was drawn to the newest fix. */
  positionAt(v: VehicleState, now: number): { lat: number; lon: number } {
    if (v.glideMs <= 0) {
      return { lat: v.current.lat, lon: v.current.lon };
    }
    const f = Math.min(1, Math.max(0, (now - v.arrivedAt) / v.glideMs));
    return {
      lat: v.from.lat + (v.current.lat - v.from.lat) * f,
      lon: v.from.lon + (v.current.lon - v.from.lon) * f,
    };
  }

  status(v: VehicleState, now: number): VehicleStatus {
    if (now - v.arrivedAt > STALE_AFTER_MS) {
      return "stale";
    }
    return v.current.speedKph < IDLE_BELOW_KPH ? "idle" : "moving";
  }

  counts(now: number): Record<VehicleStatus, number> {
    const c: Record<VehicleStatus, number> = { moving: 0, idle: 0, stale: 0 };
    for (const v of this.vehicles.values()) {
      c[this.status(v, now)]++;
    }
    return c;
  }

  subscribe(fn: () => void): () => void {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  notify(): void {
    for (const fn of this.listeners) {
      fn();
    }
  }
}
