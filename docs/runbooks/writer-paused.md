# Runbook: WriterPaused / WriterStuck

## WriterPaused (paused for 2 minutes)

The database is unhealthy (connection, resources or shutdown). The writer has paused all partitions, rewound them to
the first uncommitted offset and is probing with exponential backoff. **No data is lost**: it waits in Kafka
(retention 72 h).

1. Check Postgres: `docker compose ps timescaledb`, `pg_isready`, disk space, `max_connections`.
2. Once Postgres is healthy the writer resumes by itself (`database healthy again; resuming N partitions`) and lag drains.
3. If the outage could outlast Kafka retention, extend retention on `nexus.gps.v1` first.

## WriterStuck

Five unknown database errors in a row (an SQLState outside 08/22/23/40/53/57, for example 42P01 undefined table). The
writer stopped consuming on purpose: retrying a bug forever helps nobody.

1. Read the writer log for the SQLState and message (often a missing migration or a permissions change).
2. Fix it, then `docker compose restart writer`. It resumes from the last committed offsets.
