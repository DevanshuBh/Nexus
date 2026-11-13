# ADR 0006: A Netty WebSocket gateway with per-client coalescing

Status: accepted

## Context
Fan-out dominates at scale (see design §3). A per-connection queue of every ping lets one slow browser consume
unbounded memory.

## Decision
Netty server; each session keeps a map of the newest pending position per truck (capped), flushed as one frame every
250 ms only while `channel.isWritable()` (write-buffer watermarks 64 KB / 256 KB). Redis Pub/Sub (pattern subscribe)
feeds every node; any node can serve any browser.

## Consequences
- Memory per connection is bounded by the trucks it watches, not by time spent slow. Overwrites are counted
  (`nexus_gateway_coalesced`), as are deferred flushes and drops.
- Positions may skip intermediate fixes for slow clients; acceptable for a live map (history comes from the DB).
- Classic Pub/Sub broadcasts every message to every node; at scale, per-fleet sharded Pub/Sub or H3-cell channels.
