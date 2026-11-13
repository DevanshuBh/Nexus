-- GPS history as a TimescaleDB hypertable partitioned on event time.
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE gps_events (
    vehicle_id  text             NOT NULL,
    event_ts    timestamptz      NOT NULL,
    event_id    uuid             NOT NULL,
    fleet_id    text             NOT NULL,
    lat         double precision NOT NULL CHECK (lat BETWEEN -90 AND 90),
    lon         double precision NOT NULL CHECK (lon BETWEEN -180 AND 180),
    speed_kph   real             NOT NULL CHECK (speed_kph >= 0 AND speed_kph <= 400),
    heading     real             NOT NULL CHECK (heading >= 0 AND heading <= 360),
    received_at timestamptz      NOT NULL
);

-- Chunk interval: 6 hours. At the 1,000-truck design point (~29M rows/day, ~3-6 GB/day with
-- indexes) a 6-hour chunk is ~1-1.5 GB, so the newest chunk and its index fit in shared_buffers
-- on the 12 GB demo VM. See docs/adr/0002-chunk-interval.md.
-- Default indexes are off: the only query path (one vehicle over a time range) is served by the
-- dedup index below, and every extra index costs write throughput.
SELECT create_hypertable('gps_events', by_range('event_ts', INTERVAL '6 hours'), create_default_indexes => false);

-- Dedup key. On a hypertable every unique index must include the partitioning column (event_ts).
-- Replays and redeliveries hit this index and are dropped by ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX gps_events_dedup ON gps_events (vehicle_id, event_ts, event_id);
