package ca.sfu.bustracker.collector;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class RealtimeStore {
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;

    public RealtimeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryConsumeRequest(LocalDate day, int budget) {
        List<Integer> counts = jdbc.query("""
                INSERT INTO api_request (day, count) VALUES (?, 1)
                ON CONFLICT (day) DO UPDATE
                    SET count = api_request.count + 1
                    WHERE api_request.count < ?
                RETURNING count
                """, (rs, row) -> rs.getInt(1), day, budget);
        return !counts.isEmpty();
    }

    public void insertFetch(Instant fetchedAt, String feedType, Integer httpStatus, long latencyMs,
                             Integer entityCount, Instant feedTimestamp, String error) {
        jdbc.update("""
                INSERT INTO feed_fetch (fetched_at, feed_type, http_status, latency_ms, entity_count, feed_timestamp, error)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Timestamp.from(fetchedAt), feedType, httpStatus, (int) Math.min(latencyMs, Integer.MAX_VALUE),
                entityCount, feedTimestamp == null ? null : Timestamp.from(feedTimestamp), error);
    }

    public void insertPositions(Instant feedTimestamp, List<DecodedFeed.VehicleFix> vehicles) {
        Timestamp ts = Timestamp.from(feedTimestamp);
        for (int i = 0; i < vehicles.size(); i += BATCH) {
            List<DecodedFeed.VehicleFix> chunk = vehicles.subList(i, Math.min(i + BATCH, vehicles.size()));
            jdbc.batchUpdate("""
                    INSERT INTO vehicle_position
                        (feed_timestamp, vehicle_id, trip_id, route_id, lat, lon, current_stop_sequence, current_status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (vehicle_id, feed_timestamp) DO NOTHING
                    """, chunk, chunk.size(), (ps, v) -> {
                ps.setTimestamp(1, ts);
                ps.setString(2, v.vehicleId());
                ps.setString(3, v.tripId());
                ps.setString(4, v.routeId());
                if (v.lat() == null) ps.setNull(5, java.sql.Types.DOUBLE); else ps.setDouble(5, v.lat());
                if (v.lon() == null) ps.setNull(6, java.sql.Types.DOUBLE); else ps.setDouble(6, v.lon());
                if (v.currentStopSequence() == null) ps.setNull(7, java.sql.Types.INTEGER); else ps.setInt(7, v.currentStopSequence());
                ps.setString(8, v.currentStatus());
            });
        }
        for (DecodedFeed.VehicleFix v : vehicles) {
            if (v.serviceDate() == null || v.tripId() == null || v.currentStopSequence() == null) {
                continue;
            }
            jdbc.update("""
                    UPDATE stop_arrival
                    SET position_arrival = ?
                    WHERE service_date = ? AND trip_id = ? AND stop_sequence < ? AND position_arrival IS NULL
                    """, ts, v.serviceDate(), v.tripId(), v.currentStopSequence());
        }
    }

    public void applyTrips(Instant seenAt, List<DecodedFeed.TripSnapshot> trips) {
        Timestamp seen = Timestamp.from(seenAt);
        List<Object[]> arrivals = new java.util.ArrayList<>();
        for (DecodedFeed.TripSnapshot trip : trips) {
            if (trip.serviceDate() == null || trip.tripId() == null) {
                continue;
            }
            if (trip.canceled()) {
                jdbc.update("""
                        INSERT INTO trip_notice (service_date, trip_id, relationship, last_seen_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (service_date, trip_id) DO UPDATE
                            SET relationship = EXCLUDED.relationship, last_seen_at = EXCLUDED.last_seen_at
                        """, trip.serviceDate(), trip.tripId(), trip.relationship(), seen);
                continue;
            }
            StringBuilder sequences = new StringBuilder();
            for (DecodedFeed.StopPrediction stop : trip.stops()) {
                if (sequences.length() > 0) sequences.append(',');
                sequences.append(stop.stopSequence());
                arrivals.add(new Object[]{
                        trip.serviceDate(), trip.tripId(), stop.stopSequence(), stop.stopId(),
                        stop.predictedArrival() == null ? null : Timestamp.from(stop.predictedArrival()),
                        seen, trip.relationship()
                });
            }
            if (sequences.length() > 0) {
                jdbc.update("""
                        UPDATE stop_arrival
                        SET finalized = true,
                            observed_arrival = COALESCE(observed_arrival, predicted_arrival),
                            source = COALESCE(source, 'prediction')
                        WHERE service_date = ? AND trip_id = ? AND finalized = false
                          AND stop_sequence <> ALL (string_to_array(?, ',')::int[])
                        """, trip.serviceDate(), trip.tripId(), sequences.toString());
            }
        }
        for (int i = 0; i < arrivals.size(); i += BATCH) {
            List<Object[]> chunk = arrivals.subList(i, Math.min(i + BATCH, arrivals.size()));
            jdbc.batchUpdate("""
                    INSERT INTO stop_arrival
                        (service_date, trip_id, stop_sequence, stop_id, predicted_arrival, last_seen_at, schedule_relationship)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (service_date, trip_id, stop_sequence) DO UPDATE SET
                        stop_id = COALESCE(EXCLUDED.stop_id, stop_arrival.stop_id),
                        predicted_arrival = COALESCE(EXCLUDED.predicted_arrival, stop_arrival.predicted_arrival),
                        last_seen_at = EXCLUDED.last_seen_at,
                        schedule_relationship = COALESCE(EXCLUDED.schedule_relationship, stop_arrival.schedule_relationship)
                    """, chunk, chunk.size(), (ps, row) -> {
                ps.setObject(1, row[0]);
                ps.setString(2, (String) row[1]);
                ps.setInt(3, (Integer) row[2]);
                ps.setString(4, (String) row[3]);
                ps.setTimestamp(5, (Timestamp) row[4]);
                ps.setTimestamp(6, (Timestamp) row[5]);
                ps.setString(7, (String) row[6]);
            });
        }
    }
}
