/** A vehicle position as the gateway and the query API send it (same shape as dev.nexus.common.Position). */
export interface Position {
  vehicleId: string;
  fleetId: string;
  /** device event time, epoch ms */
  ts: number;
  lat: number;
  lon: number;
  speedKph: number;
  heading: number;
}

export interface TrackPoint {
  ts: number;
  lat: number;
  lon: number;
  speedKph: number;
}

export type VehicleStatus = "moving" | "idle" | "stale";

export type ConnectionState = "connecting" | "live" | "reconnecting" | "demo" | "error";

export interface LiveConfig {
  queryUrl: string;
  gatewayUrl: string;
  fleets: string[];
}

/** Messages from the main thread to the fleet worker. */
export type ToWorker =
  | { type: "start-demo"; trucks: number; seed?: number }
  | { type: "start-live"; config: LiveConfig }
  | { type: "track"; vehicleId: string; fleetId: string; from: number; to: number };

export interface WorkerStats {
  /** positions received by the worker per second (before coalescing) */
  inPerSec: number;
  /** positions posted to the main thread per second (after coalescing) */
  outPerSec: number;
}

/** Messages from the fleet worker to the main thread. */
export type FromWorker =
  | { type: "batch"; items: Position[]; stats: WorkerStats }
  | { type: "snapshot"; items: Position[] }
  | { type: "status"; state: ConnectionState; detail?: string }
  | { type: "track"; vehicleId: string; points: TrackPoint[]; resolution: string };
