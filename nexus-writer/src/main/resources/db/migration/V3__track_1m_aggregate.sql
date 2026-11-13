-- One-minute downsampled tracks for long route-history ranges.
-- WITH NO DATA lets this run inside Flyway's transaction; the policy fills it.
CREATE MATERIALIZED VIEW gps_track_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
SELECT vehicle_id,
       fleet_id,
       time_bucket(INTERVAL '1 minute', event_ts) AS bucket,
       last(lat, event_ts)                        AS lat,
       last(lon, event_ts)                        AS lon,
       avg(speed_kph)                             AS avg_speed_kph,
       max(speed_kph)                             AS max_speed_kph,
       count(*)                                   AS pings
FROM gps_events
GROUP BY vehicle_id, fleet_id, bucket
WITH NO DATA;

SELECT add_continuous_aggregate_policy('gps_track_1m',
    start_offset      => INTERVAL '3 days',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');
