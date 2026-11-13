# Runbook: EventAgeP99High (p99 event age > 10 s for 5 min)

**Meaning:** pings take too long from device time to storage; trucks show up late on the map and in history.

1. Is Kafka lag high? Dashboard panel "Kafka: consumer lag per partition".
   - All partitions high: the writers are behind. Check "Writer: batch write latency" and database CPU; see
     [writer-paused.md](writer-paused.md).
   - One partition high: a hot key; see [partition-lag.md](partition-lag.md).
2. Lag low but event age high: the delay is before Kafka.
   - Devices buffering in dead zones, then uploading backlog: the ingest accepted rate spikes after a quiet period.
   - Device clock skew: only some devices are affected; compare `received_at - event_ts` per device in SQL.
   - Ingest saturated: ingest CPU near one core and rising `http_server_requests_seconds`. Scale ingest out.
3. If a known backlog upload caused it, the alert clears by itself; record it in `docs/notebooks/incidents.md`.
