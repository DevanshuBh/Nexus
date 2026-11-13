import type { Position, TrackPoint } from "./types";

/**
 * In-browser fleet simulator used by the public demo (no back end needed). It mirrors the Java device simulator:
 * trucks drive between random waypoints around Bengaluru, stop for deliveries, some go offline, and a few send
 * late, out-of-order pings so the console's never-backwards guard has something to reject.
 */

const CENTER = { lat: 12.9716, lon: 77.5946 };
const RADIUS_KM = 16;
const PING_MS = 3_000;
const HISTORY = 200; // points kept per truck for route playback (10 minutes at 3 s)

/** Small seeded PRNG (mulberry32) so a demo run is reproducible in tests. */
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface Truck {
  id: string;
  fleetId: string;
  lat: number;
  lon: number;
  tLat: number;
  tLon: number;
  speed: number;
  heading: number;
  stoppedUntil: number;
  offlineUntil: number;
  nextPingAt: number;
  // ring buffer of [ts, lat, lon, speed] for playback
  hist: Float64Array;
  histLen: number;
  histHead: number;
}

export class DemoFleet {
  private readonly trucks: Truck[] = [];
  private readonly rand: () => number;
  private clock: number;

  constructor(count: number, now: number, seed = 42) {
    this.rand = rng(seed);
    for (let i = 0; i < count; i++) {
      const [lat, lon] = this.randomPoint();
      const t: Truck = {
        id: `f0${(i % 2) + 1}-v${String(i + 1).padStart(5, "0")}`,
        fleetId: `f0${(i % 2) + 1}`,
        lat,
        lon,
        tLat: lat,
        tLon: lon,
        speed: 0,
        heading: 0,
        stoppedUntil: 0,
        offlineUntil: 0,
        nextPingAt: 0,
        hist: new Float64Array(HISTORY * 4),
        histLen: 0,
        histHead: 0,
      };
      this.pickTarget(t);
      this.trucks.push(t);
    }
    // Pre-roll 10 minutes so route playback has history from the first second.
    this.clock = now - HISTORY * PING_MS;
    for (const t of this.trucks) {
      t.nextPingAt = this.clock + this.rand() * PING_MS;
    }
    this.advance(now);
  }

  private randomPoint(): [number, number] {
    const r = (RADIUS_KM / 111) * Math.sqrt(this.rand());
    const a = this.rand() * 2 * Math.PI;
    return [CENTER.lat + r * Math.sin(a), CENTER.lon + (r * Math.cos(a)) / Math.cos((CENTER.lat * Math.PI) / 180)];
  }

  private pickTarget(t: Truck): void {
    [t.tLat, t.tLon] = this.randomPoint();
    t.speed = 18 + this.rand() * 38;
  }

  /** Moves every truck forward to `now`; returns the pings generated, oldest first. */
  advance(now: number): Position[] {
    const out: Position[] = [];
    for (const t of this.trucks) {
      while (t.nextPingAt <= now) {
        const ts = t.nextPingAt;
        this.move(t, PING_MS, ts);
        t.nextPingAt += PING_MS;
        const p: Position = {
          vehicleId: t.id,
          fleetId: t.fleetId,
          ts: Math.round(ts),
          lat: t.lat,
          lon: t.lon,
          speedKph: ts < t.stoppedUntil ? 0 : Math.round(t.speed * 10) / 10,
          heading: Math.round(t.heading),
        };
        this.record(t, p);
        if (ts < t.offlineUntil) {
          continue; // offline: the device buffers; the console sees it go stale
        }
        if (this.rand() < 0.0008) {
          t.offlineUntil = ts + 70_000 + this.rand() * 60_000;
        }
        out.push(p);
        // ~0.3% of pings are followed by a late duplicate of an older fix (redelivery / replay)
        if (t.histLen > 10 && this.rand() < 0.003) {
          const old = this.historyAt(t, t.histLen - 8);
          out.push({ ...p, ts: old.ts, lat: old.lat, lon: old.lon });
        }
      }
    }
    this.clock = now;
    return out;
  }

  private move(t: Truck, dtMs: number, ts: number): void {
    if (ts < t.stoppedUntil) {
      return;
    }
    const cosLat = Math.cos((t.lat * Math.PI) / 180);
    const dLat = t.tLat - t.lat;
    const dLon = (t.tLon - t.lon) * cosLat;
    const distKm = Math.hypot(dLat, dLon) * 111;
    const stepKm = (t.speed * dtMs) / 3_600_000;
    t.heading = ((Math.atan2(dLon, dLat) * 180) / Math.PI + 360) % 360;
    if (stepKm >= distKm) {
      t.lat = t.tLat;
      t.lon = t.tLon;
      this.pickTarget(t);
      if (this.rand() < 0.35) {
        t.stoppedUntil = ts + 15_000 + this.rand() * 90_000; // delivery stop
      }
    } else {
      const f = stepKm / distKm;
      t.lat += (t.tLat - t.lat) * f;
      t.lon += (t.tLon - t.lon) * f;
    }
  }

  private record(t: Truck, p: Position): void {
    const i = t.histHead * 4;
    t.hist[i] = p.ts;
    t.hist[i + 1] = p.lat;
    t.hist[i + 2] = p.lon;
    t.hist[i + 3] = p.speedKph;
    t.histHead = (t.histHead + 1) % HISTORY;
    t.histLen = Math.min(HISTORY, t.histLen + 1);
  }

  /** k-th oldest history point (0 = oldest kept) */
  private historyAt(t: Truck, k: number): TrackPoint {
    const start = (t.histHead - t.histLen + HISTORY) % HISTORY;
    const i = ((start + k) % HISTORY) * 4;
    return { ts: t.hist[i]!, lat: t.hist[i + 1]!, lon: t.hist[i + 2]!, speedKph: t.hist[i + 3]! };
  }

  /** Newest known fix of every truck, like the query API's fleet snapshot. */
  snapshot(): Position[] {
    return this.trucks
      .filter((t) => t.histLen > 0)
      .map((t) => {
        const p = this.historyAt(t, t.histLen - 1);
        return { vehicleId: t.id, fleetId: t.fleetId, ts: p.ts, lat: p.lat, lon: p.lon, speedKph: p.speedKph, heading: Math.round(t.heading) };
      });
  }

  track(vehicleId: string, from: number, to: number): TrackPoint[] {
    const t = this.trucks.find((x) => x.id === vehicleId);
    if (!t) {
      return [];
    }
    const out: TrackPoint[] = [];
    for (let k = 0; k < t.histLen; k++) {
      const p = this.historyAt(t, k);
      if (p.ts >= from && p.ts <= to) {
        out.push(p);
      }
    }
    return out;
  }

  get size(): number {
    return this.trucks.length;
  }
}
