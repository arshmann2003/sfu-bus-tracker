CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE feed_version (
    id          bigserial PRIMARY KEY,
    sha256      text NOT NULL UNIQUE,
    valid_from  date,
    valid_to    date,
    imported_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE route (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    route_id        text NOT NULL,
    short_name      text,
    long_name       text,
    route_type      int,
    PRIMARY KEY (feed_version_id, route_id)
);

CREATE TABLE stop (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    stop_id         text NOT NULL,
    name            text,
    lat             double precision,
    lon             double precision,
    PRIMARY KEY (feed_version_id, stop_id)
);

CREATE TABLE trip (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    trip_id         text NOT NULL,
    route_id        text,
    service_id      text,
    direction_id    int,
    headsign        text,
    PRIMARY KEY (feed_version_id, trip_id)
);

CREATE TABLE stop_time (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    trip_id         text NOT NULL,
    stop_sequence   int NOT NULL,
    stop_id         text,
    arrival_secs    int,
    departure_secs  int,
    PRIMARY KEY (feed_version_id, trip_id, stop_sequence)
);

CREATE TABLE calendar (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    service_id      text NOT NULL,
    monday          boolean NOT NULL,
    tuesday         boolean NOT NULL,
    wednesday       boolean NOT NULL,
    thursday        boolean NOT NULL,
    friday          boolean NOT NULL,
    saturday        boolean NOT NULL,
    sunday          boolean NOT NULL,
    start_date      date,
    end_date        date,
    PRIMARY KEY (feed_version_id, service_id)
);

CREATE TABLE calendar_date (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    service_id      text NOT NULL,
    service_date    date NOT NULL,
    exception_type  int NOT NULL,
    PRIMARY KEY (feed_version_id, service_id, service_date)
);

CREATE TABLE frequency (
    feed_version_id bigint NOT NULL REFERENCES feed_version (id),
    trip_id         text NOT NULL,
    start_secs      int NOT NULL,
    end_secs        int NOT NULL,
    headway_secs    int NOT NULL,
    PRIMARY KEY (feed_version_id, trip_id, start_secs)
);

CREATE TABLE feed_fetch (
    fetched_at      timestamptz NOT NULL,
    feed_type       text NOT NULL,
    http_status     int,
    latency_ms      int,
    entity_count    int,
    feed_timestamp  timestamptz,
    error           text
);
SELECT create_hypertable('feed_fetch', 'fetched_at');

CREATE TABLE vehicle_position (
    feed_timestamp         timestamptz NOT NULL,
    vehicle_id             text NOT NULL,
    trip_id                text,
    route_id               text,
    lat                    double precision,
    lon                    double precision,
    current_stop_sequence  int,
    current_status         text,
    PRIMARY KEY (vehicle_id, feed_timestamp)
);
SELECT create_hypertable('vehicle_position', 'feed_timestamp');
ALTER TABLE vehicle_position SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'vehicle_id'
);
SELECT add_compression_policy('vehicle_position', INTERVAL '2 days');
SELECT add_retention_policy('vehicle_position', INTERVAL '90 days');

CREATE TABLE stop_arrival (
    service_date            date NOT NULL,
    trip_id                 text NOT NULL,
    stop_sequence           int NOT NULL,
    stop_id                 text,
    predicted_arrival       timestamptz,
    position_arrival        timestamptz,
    observed_arrival        timestamptz,
    source                  text,
    last_seen_at            timestamptz,
    finalized               boolean NOT NULL DEFAULT false,
    schedule_relationship   text,
    PRIMARY KEY (service_date, trip_id, stop_sequence)
);

CREATE TABLE trip_notice (
    service_date    date NOT NULL,
    trip_id         text NOT NULL,
    relationship    text NOT NULL,
    last_seen_at    timestamptz NOT NULL,
    PRIMARY KEY (service_date, trip_id)
);

CREATE TABLE api_request (
    day     date PRIMARY KEY,
    count   int NOT NULL
);
