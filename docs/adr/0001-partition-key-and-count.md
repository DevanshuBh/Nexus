# ADR 0001: Kafka key = vehicleId, 12 partitions

Status: accepted

## Context
Positions of one truck must be processed in order (the live map and trip logic depend on it). Throughput needs
parallel writers.

## Decision
Key every record by `vehicleId`; create `nexus.gps.v1` with 12 partitions, explicitly (auto-create is disabled).

## Consequences
- Per-truck order is guaranteed within a partition; up to 12 writer instances can share the load. A 13th writer
  would sit idle: consumers beyond the partition count get no partitions.
- A misbehaving device sending 100x is a hot key: it concentrates lag on one partition. Defence: per-device token
  bucket at ingest (429). A busy *region* is not a hot partition: its trucks hash across all partitions.
- The count is effectively permanent. Adding partitions changes `hash(key) % n`, so in-flight data for a truck can be
  split across two partitions and reordered. Choose with headroom now (12 ≫ the 1–4 writers needed at 5,000 trucks).

## Alternatives
- Key by fleet: fewer, hotter keys; one big fleet would serialise on one partition.
- No key (round-robin): best balance, no ordering. Rejected.
