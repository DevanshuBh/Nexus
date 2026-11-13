import { describe, expect, it } from "vitest";
import { FleetStore, STALE_AFTER_MS } from "./fleet-store";
import type { Position } from "./types";

const pos = (ts: number, lat: number, speedKph = 30): Position => ({
  vehicleId: "f01-v00001",
  fleetId: "f01",
  ts,
  lat,
  lon: 77.6,
  speedKph,
  heading: 0,
});

describe("FleetStore", () => {
  it("never moves a vehicle backwards in time", () => {
    const s = new FleetStore();
    s.apply([pos(2_000, 12.9)], 0);
    expect(s.apply([pos(1_000, 10.0)], 10)).toBe(0); // older: replay or late snapshot
    expect(s.apply([pos(2_000, 11.0)], 20)).toBe(0); // same ts: duplicate
    expect(s.vehicles.get("f01-v00001")?.current.lat).toBe(12.9);
    expect(s.rejectedBackwards).toBe(2);
  });

  it("a reconnect snapshot older than live data is ignored", () => {
    const s = new FleetStore();
    s.apply([pos(5_000, 13.0)], 0); // live update arrived first
    s.apply([pos(4_000, 12.0)], 1); // snapshot fetched a moment earlier
    expect(s.vehicles.get("f01-v00001")?.current.ts).toBe(5_000);
  });

  it("glides from the drawn position to the new fix", () => {
    const s = new FleetStore();
    s.apply([pos(0, 10)], 0);
    s.apply([pos(3_000, 11)], 1_000);
    const v = s.vehicles.get("f01-v00001")!;
    expect(s.positionAt(v, 1_000).lat).toBeCloseTo(10);
    expect(s.positionAt(v, 2_500).lat).toBeCloseTo(10.5);
    expect(s.positionAt(v, 10_000).lat).toBeCloseTo(11);
  });

  it("a new fix mid-glide starts from where the truck is drawn, not where it was", () => {
    const s = new FleetStore();
    s.apply([pos(0, 10)], 0);
    s.apply([pos(4_000, 12)], 0); // glide 10 -> 12 over 4 s
    s.apply([pos(8_000, 14)], 2_000); // halfway: drawn at 11
    expect(s.vehicles.get("f01-v00001")!.from.lat).toBeCloseTo(11);
  });

  it("classifies moving, idle and stale", () => {
    const s = new FleetStore();
    s.apply([pos(1, 10, 40), { ...pos(1, 10, 0), vehicleId: "idle" }], 0);
    expect(s.counts(1_000)).toEqual({ moving: 1, idle: 1, stale: 0 });
    expect(s.counts(STALE_AFTER_MS + 1)).toEqual({ moving: 0, idle: 0, stale: 2 });
  });
});
