# ADR 0003: COPY into a reused temp table, then INSERT … ON CONFLICT DO NOTHING

Status: accepted

## Context
COPY is the fastest bulk load into Postgres, but one duplicate key fails the whole COPY, and redelivery after a
crash is guaranteed to produce duplicates.

## Decision
Per batch, in one transaction: COPY into `gps_staging` (temp, `ON COMMIT DELETE ROWS`), then
`INSERT INTO gps_events SELECT … FROM gps_staging ON CONFLICT DO NOTHING`. The temp table is created once per physical
connection through HikariCP's `connectionInitSql`, not per batch: creating a temp table thousands of times an hour
bloats the system catalogue (pg_class/pg_attribute churn and vacuum load).

## Consequences
- Replays and redeliveries are free and silent (counted as `nexus_writer_events_duplicate`).
- One extra copy of each batch inside the server; measured write latency is ~7 ms per ~190-row batch at 1,000 trucks.
- `INSERT … ON CONFLICT` into a *compressed* chunk is expensive; compression is delayed (7 days) beyond the replay window.
