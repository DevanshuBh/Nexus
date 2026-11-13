# Runbook: SinglePartitionLagHigh (one partition > 5,000 records behind)

**Meaning:** one partition receives more than one writer can handle while the others are fine. That is a hot key: a
device (or a few) sending far above the normal rate. It delays every truck hashed to the same partition.

1. Confirm the skew on "Kafka: consumer lag per partition" (one line far above the rest).
2. Find the device: `nexus_ingest_throttled_total` rising for a fleet, or in SQL:
   ```sql
   SELECT vehicle_id, count(*) FROM gps_events
   WHERE received_at > now() - interval '5 minutes'
   GROUP BY 1 ORDER BY 2 DESC LIMIT 10;
   ```
3. The per-device rate limit (`DEVICE_RATE_LIMIT`, pings/s) caps it at ingress. Lower it, or revoke the device:
   `redis-cli SADD nexus:revoked <fleet>/<device>/*`.
4. Do **not** add partitions to the live topic: that re-maps keys and breaks per-truck ordering ([ADR 0001](../adr/0001-partition-key-and-count.md)).
