import { describe, expect, it } from "vitest";
import { DemoFleet } from "./demo-fleet";
import { FleetStore } from "./fleet-store";

describe("DemoFleet", () => {
  it("emits about one ping per truck every 3 s, with valid coordinates", () => {
    const now = 1_800_000_000_000;
    const fleet = new DemoFleet(100, now, 1);
    const pings = fleet.advance(now + 30_000);
    // 100 trucks x 10 intervals, minus offline trucks, plus a few late duplicates
    expect(pings.length).toBeGreaterThan(900);
    expect(pings.length).toBeLessThan(1_100);
    for (const p of pings) {
      expect(Math.abs(p.lat - 12.97)).toBeLessThan(0.3);
      expect(Math.abs(p.lon - 77.59)).toBeLessThan(0.3);
      expect(p.speedKph).toBeGreaterThanOrEqual(0);
    }
  });

  it("keeps 10 minutes of history for playback from the start", () => {
    const now = 1_800_000_000_000;
    const fleet = new DemoFleet(5, now, 2);
    const track = fleet.track("f01-v00001", now - 600_000, now);
    expect(track.length).toBeGreaterThan(150);
    for (let i = 1; i < track.length; i++) {
      expect(track[i]!.ts).toBeGreaterThan(track[i - 1]!.ts);
    }
  });

  it("late duplicates are rejected by the store, so trucks never jump back", () => {
    const now = 1_800_000_000_000;
    const fleet = new DemoFleet(500, now, 3);
    const store = new FleetStore();
    store.apply(fleet.advance(now + 300_000), 0);
    expect(store.rejectedBackwards).toBeGreaterThan(0);
    expect(store.vehicles.size).toBe(500);
  });
});
