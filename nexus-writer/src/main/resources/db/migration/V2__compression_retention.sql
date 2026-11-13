-- Compression: segment by vehicle so a track query decompresses only that vehicle's segment.
ALTER TABLE gps_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'vehicle_id',
    timescaledb.compress_orderby = 'event_ts DESC, event_id'
);

-- Compress after 7 days. This must be longer than the replay / late-data window
-- (Kafka retention is 3 days, devices may upload backlog up to 7 days old), so that
-- redelivered rows land in uncompressed chunks where ON CONFLICT is cheap.
SELECT add_compression_policy('gps_events', INTERVAL '7 days');

SELECT add_retention_policy('gps_events', INTERVAL '90 days');
