package ca.sfu.bustracker.collector;

import ca.sfu.bustracker.GtfsTime;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class ScheduleLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ScheduleLoader.class);
    private static final int BATCH = 2000;
    private static final CSVFormat CSV = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final HttpClient http;
    private final CollectorProperties props;

    public ScheduleLoader(JdbcTemplate jdbc, TransactionTemplate tx, HttpClient http, CollectorProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.http = http;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    @Scheduled(cron = "0 15 4 * * *", zone = "America/Vancouver")
    public void load() {
        Path zip = null;
        try {
            zip = Files.createTempFile("google_transit", ".zip");
            HttpRequest request = HttpRequest.newBuilder(URI.create(props.getStaticGtfsUrl()))
                    .timeout(props.getHttpTimeout().multipliedBy(6))
                    .GET()
                    .build();
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(zip));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Static GTFS HTTP " + response.statusCode());
            }
            String sha = sha256(zip);
            Integer existing = jdbc.query("""
                    SELECT 1 FROM feed_version WHERE sha256 = ?
                    """, rs -> rs.next() ? 1 : null, sha);
            if (existing != null) {
                log.info("Static GTFS unchanged ({})", sha.substring(0, 12));
                return;
            }
            importZip(zip, sha);
        } catch (Exception e) {
            log.error("Static GTFS import failed", e);
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (Exception ignored) {
                    log.warn("Could not delete {}", zip);
                }
            }
        }
    }

    private void importZip(Path zip, String sha) throws Exception {
        tx.executeWithoutResult(status -> {
            try (ZipFile archive = new ZipFile(zip.toFile())) {
                LocalDate validFrom = feedStart(archive);
                Long id = jdbc.queryForObject("""
                        INSERT INTO feed_version (sha256, valid_from) VALUES (?, ?) RETURNING id
                        """, Long.class, sha, validFrom);
                jdbc.update("""
                        UPDATE feed_version SET valid_to = ? WHERE id <> ? AND valid_to IS NULL
                        """, validFrom, id);
                int routes = insertRoutes(archive, id);
                int stops = insertStops(archive, id);
                int trips = insertTrips(archive, id);
                int stopTimes = insertStopTimes(archive, id);
                int calendar = insertCalendar(archive, id);
                int dates = insertCalendarDates(archive, id);
                int frequencies = insertFrequencies(archive, id);
                List<String> sfu = jdbc.query("""
                        SELECT short_name FROM route
                        WHERE feed_version_id = ? AND short_name IN ('R5','135','143','144','145')
                        ORDER BY short_name
                        """, (rs, n) -> rs.getString(1), id);
                log.info("Imported GTFS {}: {} routes, {} stops, {} trips, {} stop times, {} calendar, {} exceptions, {} frequencies. SFU routes present: {}",
                        sha.substring(0, 12), routes, stops, trips, stopTimes, calendar, dates, frequencies, sfu);
                if (frequencies == 0) {
                    log.info("frequencies.txt was absent or empty");
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private LocalDate feedStart(ZipFile archive) throws Exception {
        LocalDate[] found = new LocalDate[1];
        read(archive, "feed_info.txt", row -> {
            if (found[0] == null && row.isMapped("feed_start_date")) {
                found[0] = GtfsTime.parseServiceDate(row.get("feed_start_date"));
            }
        });
        return found[0] != null ? found[0] : LocalDate.now(ZoneId.of(props.getZone()));
    }

    private int insertRoutes(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        read(archive, "routes.txt", row -> rows.add(new Object[]{
                version, row.get("route_id"), text(row, "route_short_name"), text(row, "route_long_name"), integer(row, "route_type")
        }));
        batch(rows, """
                INSERT INTO route (feed_version_id, route_id, short_name, long_name, route_type)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """);
        return rows.size();
    }

    private int insertStops(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        read(archive, "stops.txt", row -> rows.add(new Object[]{
                version, row.get("stop_id"), text(row, "stop_name"), decimal(row, "stop_lat"), decimal(row, "stop_lon")
        }));
        batch(rows, """
                INSERT INTO stop (feed_version_id, stop_id, name, lat, lon)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """);
        return rows.size();
    }

    private int insertTrips(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        read(archive, "trips.txt", row -> rows.add(new Object[]{
                version, row.get("trip_id"), text(row, "route_id"), text(row, "service_id"),
                integer(row, "direction_id"), text(row, "trip_headsign")
        }));
        batch(rows, """
                INSERT INTO trip (feed_version_id, trip_id, route_id, service_id, direction_id, headsign)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """);
        return rows.size();
    }

    private int insertStopTimes(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        int[] count = {0};
        read(archive, "stop_times.txt", row -> {
            rows.add(new Object[]{
                    version, row.get("trip_id"), Integer.parseInt(row.get("stop_sequence")), text(row, "stop_id"),
                    GtfsTime.parseSeconds(text(row, "arrival_time")), GtfsTime.parseSeconds(text(row, "departure_time"))
            });
            if (rows.size() >= BATCH) {
                batch(rows, stopTimeSql());
                count[0] += rows.size();
                rows.clear();
            }
        });
        batch(rows, stopTimeSql());
        return count[0] + rows.size();
    }

    private static String stopTimeSql() {
        return """
                INSERT INTO stop_time (feed_version_id, trip_id, stop_sequence, stop_id, arrival_secs, departure_secs)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """;
    }

    private int insertCalendar(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        read(archive, "calendar.txt", row -> rows.add(new Object[]{
                version, row.get("service_id"),
                flag(row, "monday"), flag(row, "tuesday"), flag(row, "wednesday"), flag(row, "thursday"),
                flag(row, "friday"), flag(row, "saturday"), flag(row, "sunday"),
                GtfsTime.parseServiceDate(text(row, "start_date")), GtfsTime.parseServiceDate(text(row, "end_date"))
        }));
        batch(rows, """
                INSERT INTO calendar (feed_version_id, service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """);
        return rows.size();
    }

    private int insertCalendarDates(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        read(archive, "calendar_dates.txt", row -> rows.add(new Object[]{
                version, row.get("service_id"), GtfsTime.parseServiceDate(row.get("date")), Integer.parseInt(row.get("exception_type"))
        }));
        batch(rows, """
                INSERT INTO calendar_date (feed_version_id, service_id, service_date, exception_type)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """);
        return rows.size();
    }

    private int insertFrequencies(ZipFile archive, long version) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        read(archive, "frequencies.txt", row -> rows.add(new Object[]{
                version, row.get("trip_id"),
                GtfsTime.parseSeconds(row.get("start_time")),
                GtfsTime.parseSeconds(row.get("end_time")),
                Integer.parseInt(row.get("headway_secs"))
        }));
        batch(rows, """
                INSERT INTO frequency (feed_version_id, trip_id, start_secs, end_secs, headway_secs)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """);
        return rows.size();
    }

    private void batch(List<Object[]> rows, String sql) {
        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Object[]> chunk = rows.subList(i, Math.min(i + BATCH, rows.size()));
            jdbc.batchUpdate(sql, chunk, chunk.size(), (ps, row) -> {
                for (int c = 0; c < row.length; c++) {
                    ps.setObject(c + 1, row[c]);
                }
            });
        }
    }

    private void read(ZipFile archive, String name, java.util.function.Consumer<CSVRecord> row) throws Exception {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) {
            log.info("{} not in this GTFS zip", name);
            return;
        }
        try (CSVParser parser = CSVParser.parse(new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8), CSV)) {
            for (CSVRecord record : parser) {
                row.accept(record);
            }
        }
    }

    private static String text(CSVRecord row, String column) {
        if (!row.isMapped(column)) return null;
        String value = row.get(column);
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer integer(CSVRecord row, String column) {
        String value = text(row, column);
        return value == null ? null : Integer.valueOf(value);
    }

    private static Double decimal(CSVRecord row, String column) {
        String value = text(row, column);
        return value == null ? null : Double.valueOf(value);
    }

    private static boolean flag(CSVRecord row, String column) {
        return "1".equals(text(row, column));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
