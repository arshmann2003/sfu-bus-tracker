-- Route scores look up one route's trips, then their arrivals. The primary key
-- leads with the service day, so a route query was scanning every arrival.
CREATE INDEX IF NOT EXISTS stop_arrival_trip_stop_day
    ON stop_arrival (trip_id, stop_sequence, service_date);
