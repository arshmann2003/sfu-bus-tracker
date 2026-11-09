-- Older arrivals and fetch logs are read far more often than they are updated.
-- Chunk and compress them so a year of collection stays small on a 4 GB machine.
-- vehicle_position already compresses after 2 days and drops after 90.

SELECT create_hypertable(
    'stop_arrival',
    'service_date',
    chunk_time_interval => INTERVAL '7 days',
    migrate_data => true
);

ALTER TABLE stop_arrival SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'trip_id',
    timescaledb.compress_orderby = 'service_date, stop_sequence'
);
SELECT add_compression_policy('stop_arrival', INTERVAL '3 days');

CREATE INDEX stop_arrival_stop_day ON stop_arrival (stop_id, service_date);
CREATE INDEX vehicle_position_trip_time ON vehicle_position (trip_id, feed_timestamp);

ALTER TABLE feed_fetch SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'feed_type',
    timescaledb.compress_orderby = 'fetched_at'
);
SELECT add_compression_policy('feed_fetch', INTERVAL '7 days');
