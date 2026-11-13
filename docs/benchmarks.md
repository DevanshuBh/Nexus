# Benchmark notebook

Method: one variable per run; the simulator runs inside the Compose network (not from the host); numbers are read from
the writer's Prometheus endpoint over a 60 s window after at least 60 s of warm-up. Event age = time stored minus
device event time, from the `nexus_event_age` histogram (bucket upper bounds, hence "≤").

Environment for every run below: Intel Core i5-14600KF (20 threads), 16 GB RAM, Windows 11 + Docker Desktop
(8 GB and 20 CPUs for the VM), all services on one machine with the memory limits in `docker-compose.yml`.
Simulator flags: 2 fleets, 3 s interval, 0.5% malformed, 5% of trucks repeatedly offline for 30–90 s, ±1.5 s clock skew.

| Date | Trucks | Offered pings/s | Stored/s | Event age p50 | Event age p99 | Consumer lag | Batch (avg rows / write ms) | First saturated |
|---|---|---|---|---|---|---|---|---|
| 2026-11-14 | 1,000 | ~333 | 329–335 | ≤ 0.22 s | ≤ 2.5 s | 0 on all 12 partitions | 189 / 6.8 ms | nothing (ingest at 33% of a core) |
| 2026-11-14 | 5,000 | ~1,667 | 1,642 | ≤ 0.20 s | ≤ 1.79 s | 0 | 505 / 14.0 ms | **ingest API ≈ 1 core (99.8%)**; writer 7%, Postgres 3%, Kafka 11% |

Notes
- p99 event age includes the deliberate ±1.5 s device clock skew and offline backlogs, which is why it is far above the
  pipeline's own latency (p50 ≈ 0.2 s).
- At 5,000 trucks batches fill to the 500-row cap, so the writer's cost per row drops (≈ 28 µs/row vs ≈ 36 µs at 1,000).
- The ingest API saturates first, at about one CPU core, while every other component idles. Next step (guide 5.3):
  profile it with JFR / async-profiler before scaling out. Suspects: per-request HMAC + SHA-256 + JSON parsing, the
  synchronous Redis nonce call, the single Kafka producer I/O thread.
- 429s at 5,000 trucks (346 in about 4 minutes) come from offline devices uploading their backlog. After the 1,000-truck
  run showed the same pattern, the burst budget was raised from 10 s to 30 s of pings.

Front end (headless Chromium with SwiftShader software WebGL, so a lower bound; real GPUs are much faster):

| Date | Trucks (demo) | Updates/s into the worker | View | FPS |
|---|---|---|---|---|
| 2026-11-14 | 2,000 | ~650 | city view, clusters | 36–45 |
| 2026-11-14 | 2,000 | ~650 | zoom 14.5, every visible truck animated | 18 |

Not yet run (planned): partition-count and batch-size sweeps, chunk-interval comparison, the 20-device hot-key run with
the rate limit disabled, and a 50,000-truck run in a planned cloud window.
