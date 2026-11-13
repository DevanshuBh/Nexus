# Incident and chaos-run notebook

## 2026-11-14 · Chaos run 1: writer kill, Postgres outage, Kafka outage (200 trucks)

Run against the full Compose stack with the simulator sending continuously.

| Step | Action | What happened | Result |
|---|---|---|---|
| 1 | `docker kill -s KILL` the writer, restart after 15 s | Group reassigned; uncommitted records redelivered | rows = distinct keys |
| 2 | `docker compose stop timescaledb` for 40 s | Writer logged `batch of 487 not written (DB_UNHEALTHY …); pausing and rewinding`, kept polling (no rebalance), probed with backoff, then `database healthy again; resuming 12 partitions` | 1 whole-batch retry, **0 DLQ entries** |
| 3 | `docker compose stop kafka` for 40 s | Ingest answered 503 + Retry-After (2,685 in total); devices buffered; backlog drained after recovery | no errors after recovery |
| 4 | Stop the simulator, let the writer drain | | **ingest acknowledged 21,311 = 21,311 rows stored = 21,311 distinct keys** |

**Finding:** during the Kafka outage devices also got 429s. The per-device rate limiter charged tokens for requests
that then failed with 503, so devices spent their budget on our outage and were throttled while uploading the backlog.
**Fix:** refund the tokens when no ping in a request was acknowledged (`DeviceRateLimiter.refund`, with a unit test).

## 2026-11-14 · Crash between DB commit and offset commit (automated)

`WriterIntegrationTest.crashAfterDbCommitBeforeOffsetCommitLosesNothingAndDuplicatesNothing` throws inside the writer
right after the Postgres commit and before `commitSync`. Asserted: rows exist and committed offsets are zero; a second
writer in the same group gets everything redelivered; final rows = 250 = distinct events; DLQ empty.

**Finding (test harness):** the first version flaked. Producing right after topic creation hit `NOT_LEADER_OR_FOLLOWER`,
and the idempotent test producer then looped on `OUT_OF_ORDER_SEQUENCE_NUMBER`.
**Fix:** wait for partition leaders before producing in tests.

## 2026-11-14 · Database outage during consumption (automated)

`databaseOutagePausesAndResumesWithoutLossOrDlq` blocks connections (`ALLOW_CONNECTIONS false` plus terminating
backends) while 200 new records arrive. Asserted: the writer reports unhealthy; after access returns, all 300 rows are
stored exactly once and nothing went to the DLQ (bisecting on a connection error would have parked good rows).

## 2026-11-14 · Gateway restart with a live browser

Console in live mode, `docker compose restart gateway`: the worker reconnected with backoff, re-subscribed, reloaded
the snapshot, and the map carried on (200 trucks, no backwards jumps: the store rejects older positions).
