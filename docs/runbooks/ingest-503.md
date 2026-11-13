# Runbook: IngestUnavailable (ingest answering 503)

**Meaning:** Kafka is not acknowledging writes within the timeout. Devices keep their pings and retry after
`Retry-After`; nothing acknowledged is lost, and their rate-limit budget is refunded for 503s.

1. Check the broker: `docker compose ps kafka`, broker logs, disk space.
2. When Kafka recovers, 503s drop to zero and devices upload their backlog. Expect an ingest spike and a temporary rise
   in event age.
3. If 503s continue while the broker is healthy, check the ingest logs for buffer exhaustion (`max.block.ms`) and its CPU.
