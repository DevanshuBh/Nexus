# ADR 0002: 6-hour hypertable chunks

Status: accepted

## Context
TimescaleDB performs best when the newest chunk and its indexes fit in memory (roughly 25% of RAM for shared_buffers).
At the 1,000-truck design point the table grows ~29 M rows/day, ~3–6 GB/day including the dedup index.

## Decision
`chunk_time_interval = 6 hours` → ~1–1.5 GB per chunk at 1,000 trucks, which fits the 3 GB shared_buffers planned
for the 12 GB free-tier VM.

## Consequences
- More chunks per day (4) means more planning overhead for long-range queries; long ranges are served from the
  1-minute continuous aggregate instead.
- At 50,000 trucks the same interval gives ~40–70 GB chunks: re-evaluate (1 hour or less) before any such run.
- Compression after 7 days works per chunk; smaller chunks compress sooner after they close.
