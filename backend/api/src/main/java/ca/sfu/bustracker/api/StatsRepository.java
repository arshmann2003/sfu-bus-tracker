package ca.sfu.bustracker.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class StatsRepository {
    static final List<String> SFU_ROUTES = List.of("R5", "143", "144", "145");

    private final JdbcTemplate jdbc;

    public StatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long latestFeed() {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM feed_version", Long.class);
        return id == null ? 0 : id;
    }

    public List<RouteRow> routes(long feedId) {
        return jdbc.query("""
                SELECT r.short_name, r.long_name, r.route_id, MIN(st.arrival_secs) AS start_secs
                FROM route r
                JOIN trip t ON t.feed_version_id = r.feed_version_id AND t.route_id = r.route_id
                JOIN stop_time st ON st.feed_version_id = t.feed_version_id AND st.trip_id = t.trip_id
                WHERE r.feed_version_id = ?
                  AND r.short_name IN ('R5', '143', '144', '145')
                  AND st.arrival_secs IS NOT NULL
                GROUP BY r.short_name, r.long_name, r.route_id, t.trip_id
                """, (rs, row) -> new RouteRow(
                rs.getString("short_name"),
                rs.getString("long_name"),
                rs.getString("route_id"),
                rs.getLong("start_secs")
        ), feedId);
    }

    public List<HourDelay> delays(long feedId, String route, LocalDate from, LocalDate to, int direction) {
        return jdbc.query("""
                SELECT (arrival_secs / 3600) % 24 AS hour,
                       COUNT(*) AS n,
                       COUNT(*) FILTER (WHERE delay_sec BETWEEN -60 AND 180) AS on_time,
                       AVG(delay_sec) AS avg_delay
                FROM (
                    SELECT st.arrival_secs,
                           EXTRACT(EPOCH FROM (
                               sa.observed_arrival - (
                                   (sa.service_date::timestamp + make_interval(secs => st.arrival_secs))
                                   AT TIME ZONE 'America/Vancouver'
                               )
                           )) AS delay_sec
                    FROM stop_arrival sa
                    JOIN trip t ON t.trip_id = sa.trip_id AND t.feed_version_id = ?
                    JOIN route r ON r.feed_version_id = t.feed_version_id AND r.route_id = t.route_id
                    JOIN stop_time st ON st.feed_version_id = t.feed_version_id
                        AND st.trip_id = sa.trip_id AND st.stop_sequence = sa.stop_sequence
                    WHERE r.short_name = ?
                      AND sa.observed_arrival IS NOT NULL
                      AND st.arrival_secs IS NOT NULL
                      AND sa.service_date BETWEEN ? AND ?
                      AND (? = -1 OR t.direction_id = ?)
                ) timed
                GROUP BY 1
                ORDER BY 1
                """, (rs, row) -> new HourDelay(
                rs.getInt("hour"),
                rs.getInt("n"),
                rs.getInt("on_time"),
                rs.getDouble("avg_delay")
        ), feedId, route, Date.valueOf(from), Date.valueOf(to), direction, direction);
    }

    public List<HourGap> gaps(long feedId, String route, LocalDate from, LocalDate to, int direction) {
        return jdbc.query("""
                SELECT hour, gap_sec
                FROM (
                    SELECT (st.arrival_secs / 3600) % 24 AS hour,
                           EXTRACT(EPOCH FROM (
                               sa.observed_arrival - LAG(sa.observed_arrival) OVER (
                                   PARTITION BY sa.service_date, sa.stop_id
                                   ORDER BY sa.observed_arrival
                               )
                           )) AS gap_sec
                    FROM stop_arrival sa
                    JOIN trip t ON t.trip_id = sa.trip_id AND t.feed_version_id = ?
                    JOIN route r ON r.feed_version_id = t.feed_version_id AND r.route_id = t.route_id
                    JOIN stop_time st ON st.feed_version_id = t.feed_version_id
                        AND st.trip_id = sa.trip_id AND st.stop_sequence = sa.stop_sequence
                    WHERE r.short_name = ?
                      AND sa.observed_arrival IS NOT NULL
                      AND sa.service_date BETWEEN ? AND ?
                      AND (? = -1 OR t.direction_id = ?)
                ) g
                WHERE gap_sec > 0 AND gap_sec <= 1200
                """, (rs, row) -> new HourGap(rs.getInt("hour"), Math.round(rs.getDouble("gap_sec"))),
                feedId, route, Date.valueOf(from), Date.valueOf(to), direction, direction);
    }

    public int cancelled(long feedId, String route, LocalDate from, LocalDate to, int direction) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT tn.trip_id)
                FROM trip_notice tn
                JOIN trip t ON t.trip_id = tn.trip_id AND t.feed_version_id = ?
                JOIN route r ON r.feed_version_id = t.feed_version_id AND r.route_id = t.route_id
                WHERE r.short_name = ?
                  AND tn.service_date BETWEEN ? AND ?
                  AND (? = -1 OR t.direction_id = ?)
                """, Integer.class, feedId, route, Date.valueOf(from), Date.valueOf(to), direction, direction);
        return count == null ? 0 : count;
    }

    public int days(long feedId, String route, LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT sa.service_date)
                FROM stop_arrival sa
                JOIN trip t ON t.trip_id = sa.trip_id AND t.feed_version_id = ?
                JOIN route r ON r.feed_version_id = t.feed_version_id AND r.route_id = t.route_id
                WHERE r.short_name = ? AND sa.service_date BETWEEN ? AND ?
                """, Integer.class, feedId, route, Date.valueOf(from), Date.valueOf(to));
        return count == null ? 0 : count;
    }

    public List<StopDelay> stops(long feedId, String route, LocalDate from, LocalDate to, int direction) {
        return jdbc.query("""
                SELECT s.name, st.stop_id, MIN(st.stop_sequence) AS seq,
                       AVG(EXTRACT(EPOCH FROM (
                           sa.observed_arrival - (
                               (sa.service_date::timestamp + make_interval(secs => st.arrival_secs))
                               AT TIME ZONE 'America/Vancouver'
                           )
                       ))) AS avg_delay,
                       COUNT(sa.observed_arrival) AS n
                FROM stop_time st
                JOIN trip t ON t.feed_version_id = st.feed_version_id AND t.trip_id = st.trip_id
                JOIN route r ON r.feed_version_id = t.feed_version_id AND r.route_id = t.route_id
                JOIN stop s ON s.feed_version_id = st.feed_version_id AND s.stop_id = st.stop_id
                LEFT JOIN stop_arrival sa
                    ON sa.trip_id = st.trip_id
                   AND sa.stop_sequence = st.stop_sequence
                   AND sa.observed_arrival IS NOT NULL
                   AND sa.service_date BETWEEN ? AND ?
                WHERE st.feed_version_id = ?
                  AND r.short_name = ?
                  AND (? = -1 OR t.direction_id = ?)
                GROUP BY s.name, st.stop_id
                HAVING COUNT(sa.observed_arrival) > 0
                ORDER BY seq
                """, (rs, row) -> new StopDelay(
                rs.getString("stop_id"),
                rs.getString("name"),
                rs.getInt("seq"),
                rs.getDouble("avg_delay"),
                rs.getInt("n")
        ), Date.valueOf(from), Date.valueOf(to), feedId, route, direction, direction);
    }

    public List<DirectionRow> directions(long feedId, String route) {
        return jdbc.query("""
                SELECT t.direction_id, string_agg(DISTINCT t.headsign, ' · ' ORDER BY t.headsign) AS headsign
                FROM trip t
                JOIN route r ON r.feed_version_id = t.feed_version_id AND r.route_id = t.route_id
                WHERE r.feed_version_id = ? AND r.short_name = ?
                GROUP BY t.direction_id
                ORDER BY 1
                """, (rs, row) -> new DirectionRow(rs.getInt("direction_id"), rs.getString("headsign")),
                feedId, route);
    }

    public StatusRow status() {
        return jdbc.queryForObject("""
                SELECT
                    (SELECT MAX(fetched_at) FROM feed_fetch) AS last_fetch,
                    (SELECT COUNT(*) FROM feed_fetch
                     WHERE fetched_at > now() - interval '1 hour' AND http_status = 200) AS ok_fetches,
                    (SELECT COUNT(*) FROM feed_fetch
                     WHERE fetched_at > now() - interval '1 hour') AS fetches,
                    (SELECT COUNT(DISTINCT service_date) FROM stop_arrival) AS days,
                    (SELECT COUNT(*) FROM stop_arrival) AS arrivals,
                    (SELECT COUNT(*) FROM stop_arrival WHERE observed_arrival IS NOT NULL) AS observed
                """, (rs, row) -> {
            java.sql.Timestamp last = rs.getTimestamp("last_fetch");
            return new StatusRow(
                    last == null ? null : last.toInstant(),
                    rs.getInt("ok_fetches"),
                    rs.getInt("fetches"),
                    rs.getInt("days"),
                    rs.getLong("arrivals"),
                    rs.getLong("observed")
            );
        });
    }

    public record RouteRow(String shortName, String longName, String routeId, long startSecs) {}
    public record HourDelay(int hour, int observations, int onTime, double avgDelaySec) {}
    public record HourGap(int hour, long gapSec) {}
    public record StopDelay(String stopId, String name, int sequence, double avgDelaySec, int observations) {}
    public record DirectionRow(int directionId, String headsign) {}
    public record StatusRow(Instant lastFetch, int okFetches, int fetches, int days, long arrivals, long observed) {}
}
