# NEXUS design

Status: implemented (core track). Last updated 2026-11-14.

## 1. Problem

Trucks report GPS every few seconds. Dispatchers need every truck on a live map within seconds, and the full history
for route playback. The data path must stay correct through process crashes, Kafka redeliveries, bad device data,
database and broker outages, and traffic spikes.

## 2. Requirements

| | Requirement | Target | Measured |
|---|---|---|---|
| F1 | Devices upload signed batches of their own pings | | done |
| F2 | Dispatchers see their fleet live; snapshot on (re)connect | | done |
| F3 | Route history for any time window | | done (raw ≤ 6 h, 1-minute aggregate beyond) |
| N1 | Lost valid events (acknowledged to the device) | 0 | 0 across 3 outages ([incidents](notebooks/incidents.md)) |
| N2 | Duplicate rows, including replays and crashes | 0 | 0 |
| N3 | Event age, device time → stored, p99 | < 10 s | ≤ 2.5 s at 1,000 trucks ([benchmarks](benchmarks.md)) |
| N4 | Live position never moves backwards in time | always | guarded in Redis and in the browser |
| N5 | One slow browser cannot slow others or grow server memory | | per-client bounded buffer |
| N6 | Tenant isolation: a dispatcher sees only their fleets | | JWT fleet claims on every read |

## 3. Estimates

Design point 50,000 trucks × 1 ping / 3 s; the laptop and free VM run 1,000–5,000.

| Quantity | 1,000 trucks | 50,000 trucks |
|---|---|---|
| Ingest | 333 pings/s | 16,700 pings/s |
| Rows/day | 29 M | 1.44 B |
| Storage/day before compression (~100–200 B/row incl. index) | 3–6 GB | 150–290 GB |
| Naive fan-out (2,000 dispatchers × 500 trucks ÷ 3 s) | | 333,000 msgs/s |
| Batched fan-out (2,000 dispatchers × 4 frames/s) | | 8,000 frames/s |

The read side (fan-out), not the write side, is the hard scaling problem: hence per-client coalescing and batching
in the gateway. 50,000 trucks is a design target to be proven only in a short, planned cloud window.

## 4. API

`POST /v1/telemetry` (device). Headers `X-Fleet-Id, X-Device-Id, X-Key-Version, X-Timestamp, X-Nonce, X-Signature`.
Signature = HMAC-SHA256(device key, `v1\nfleet\ndevice\nversion\ntimestamp\nnonce\nsha256(body)`).
Response 202 with one result per ping: `ACCEPTED` (on Kafka, never resend), `REJECTED` (invalid, never resend) or
`RETRY` (not acknowledged in time; resending is safe). 401 auth, 413 too many pings, 429 device over rate,
503 + `Retry-After` when Kafka is not accepting writes.

`GET /v1/fleets/{fleet}/positions?bbox=minLon,minLat,maxLon,maxLat` (dispatcher, bearer JWT): snapshot from Redis.
`GET /v1/fleets/{fleet}/vehicles/{vehicle}/track?from=&to=`: history from TimescaleDB.
`ws://gateway/ws?token=…`, then `{"type":"subscribe","fleetId":"f01"}`; server pushes
`{"type":"positions","items":[…]}` every 250 ms.

## 5. Data model

`gps_events(vehicle_id, event_ts, event_id, fleet_id, lat, lon, speed_kph, heading, received_at)`, a hypertable on
`event_ts` with 6-hour chunks ([ADR 0002](adr/0002-chunk-interval.md)). Unique index `(vehicle_id, event_ts, event_id)`
is both the dedup key and the only index the track query needs. CHECK constraints reject impossible values even if
something bypasses ingest. Compression after 7 days (longer than the 3-day Kafka retention and the 7-day offline
upload window), retention 90 days, continuous aggregate `gps_track_1m`.

Redis, per fleet, all under hash tag `{fleet:ID}`: `…:geo` GEO set for viewport queries, `…:v:<vehicle>` hash with
the last position. Channel `nexus:pos:<fleet>` carries live positions.

Kafka: `nexus.gps.v1`, 12 partitions, key = vehicleId (per-truck order); `nexus.gps.dlq`, 3 partitions, 14-day
retention.

## 6. Deep dives

**Exactly-once storage from at-least-once delivery.** The ingest API answers after the broker acknowledges
(`acks=all`, idempotent producer). The writer stores a batch in one transaction and commits offsets only after it;
anything before the offset commit is redelivered, and redelivered rows hit `ON CONFLICT DO NOTHING`. COPY cannot skip
conflicts, so it loads a per-connection temp staging table (created once via the pool's init SQL, emptied by
`ON COMMIT DELETE ROWS`) and an `INSERT … SELECT … ON CONFLICT DO NOTHING` moves the rows.

**Bad data vs. bad database.** SQLState class 22/23 means the data is wrong: bisect the batch until single bad rows
are found, park them in the DLQ with the reason, store the rest. Classes 08/53/57 mean the database is unhealthy:
bisecting would park good rows, so pause all partitions, `seek()` each back to its first uncommitted record (pause
alone would skip records `poll()` had already returned), keep polling so the group does not rebalance, probe health
with backoff and jitter, resume. Unknown errors retry 5 times and then stop consumption and page.

**Never backwards.** A Lua script compares the stored event time, writes the hash, updates the GEO index and
publishes, all atomically, so a replay or a slow writer cannot regress a position or push an old one to browsers.
The browser applies the same rule, which also makes snapshot-after-subscribe safe on reconnect.

**Slow consumers.** Each WebSocket session holds at most one pending position per truck (bounded by fleet size and a
hard cap). A flush every 250 ms writes one frame only if Netty reports the channel writable (64 KB / 256 KB
watermarks), so a stalled client costs a fixed amount of memory and never delays others.

## 7. Failure modes

| Edge | Slow | Down | Duplicate | Out of order |
|---|---|---|---|---|
| Device → ingest | timeouts; device retries | device buffers (offline backlog) | nonce rejects replays; eventId dedups resends | eventTs is device time; allowed |
| Ingest → Kafka | `max.block.ms` 250 ms → 503 | 503 + Retry-After; rate-limit budget refunded | idempotent producer | per-key order kept |
| Kafka → writer | lag grows; alert on single-partition lag | consumer waits | ON CONFLICT | per-partition order |
| Writer → Postgres | write latency metric | pause + seek + retry, no DLQ | ON CONFLICT | n/a |
| Writer → Redis | metric; storage unaffected | live map degraded, storage continues | Lua guard no-op | Lua guard rejects |
| Redis → gateway → browser | coalesce per client | Lettuce reconnects; browser reconnects with backoff, reloads snapshot | browser guard | browser guard |

## 8. Trade-offs and evolution

- Key = vehicleId keeps per-truck order but makes hot devices hot partitions; mitigated by per-device rate limits at
  ingress. A busy region spreads across partitions; it loads the fleet's channel and gateway queues instead.
- Partition count is effectively permanent: adding partitions re-maps keys and breaks ordering for data in flight.
- At 10x: shard Redis by fleet (sharded Pub/Sub `SPUBLISH`), more gateway nodes behind a load balancer, writers up to
  the partition count, chunk compression on schedule, cold Parquet tier for history older than N days.
- Not built yet (staff track): Protobuf device protocol with sequence numbers, H3 viewport subscriptions, Ed25519
  device keys, streaming replica and failover drill, tiered storage, the 50k cloud run.
