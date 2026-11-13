# ADR 0007: Static-export console with an in-browser demo fleet

Status: accepted

## Context
The console must be publicly viewable for free, but the back end (Kafka, TimescaleDB, Redis, four JVMs) cannot run on
a static host.

## Decision
Next.js static export hosted on Vercel. By default the console's Web Worker runs a simulated fleet (same movement model
as the Java simulator, including offline trucks and late duplicates). When built with `NEXT_PUBLIC_QUERY_URL` and
`NEXT_PUBLIC_GATEWAY_URL`, `?mode=live` connects to a real stack instead; both modes feed the same store and map code.

## Consequences
- The public demo never breaks because a free VM was reclaimed; the live path is exercised locally and in CI builds.
- The page says plainly that the hosted fleet is simulated.
