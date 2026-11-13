# Changelog

## 0.1.0 (2026-11-14)

- Ingest API: HMAC device auth with key rotation, revocation, nonce replay protection; validation; per-device rate
  limit; answers only after Kafka acknowledgement; 503 backpressure.
- Writer: plain kafka-clients loop; COPY + ON CONFLICT exactly-once storage; SQLState classification; bisection to a
  DLQ; pause/seek on database outages; rebalance-safe commits; Redis Lua never-backwards guard; DLQ replay CLI.
- TimescaleDB schema: hypertable (6 h chunks), dedup index, compression, retention, 1-minute continuous aggregate.
- Query API (snapshot via Redis GEOSEARCH, route history), Netty WebSocket gateway with per-client coalescing.
- Device simulator; Docker Compose stack; Prometheus alerts with runbooks; Grafana dashboard; CI.
- Dispatcher console: Next.js 16, MapLibre GL 6, Web Worker data path, demo and live modes.
