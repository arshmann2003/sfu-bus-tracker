package ca.sfu.bustracker.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeedPoller implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FeedPoller.class);

    private final HttpClient http;
    private final CollectorProperties props;
    private final RealtimeStore store;
    private final ZoneId zone;
    private final Map<String, Instant> lastArchive = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    public FeedPoller(HttpClient http, CollectorProperties props, RealtimeStore store) {
        this.http = http;
        this.props = props;
        this.store = store;
        this.zone = ZoneId.of(props.getZone());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.hasApiKey()) {
            log.warn("TRANSLINK_API_KEY is empty. Realtime polling is off until you set it. The static schedule still loads.");
        } else {
            log.info("Realtime polling on. Trip updates every {}s, positions every {}s, daily budget {} requests.",
                    props.getTripUpdateIntervalMs() / 1000, props.getPositionIntervalMs() / 1000, props.getDailyRequestBudget());
        }
    }

    @Scheduled(fixedDelayString = "${collector.trip-update-interval-ms:120000}", initialDelayString = "15000")
    public void pollTripUpdates() {
        poll("trip_updates", props.getTripUpdatesUrl(), true);
    }

    @Scheduled(fixedDelayString = "${collector.position-interval-ms:360000}", initialDelayString = "30000")
    public void pollPositions() {
        poll("positions", props.getPositionsUrl(), false);
    }

    private void poll(String feedType, String url, boolean pingHealthcheck) {
        if (!props.hasApiKey()) {
            return;
        }
        backoff(feedType);
        LocalDate day = LocalDate.now(zone);
        if (!store.tryConsumeRequest(day, props.getDailyRequestBudget())) {
            log.warn("Daily TransLink request budget ({}) reached; skipping {}", props.getDailyRequestBudget(), feedType);
            return;
        }
        Instant started = Instant.now();
        try {
            String key = URLEncoder.encode(props.getApiKey(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "?apikey=" + key))
                    .timeout(props.getHttpTimeout())
                    .header("Accept", "application/x-protobuf")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long latency = Instant.now().toEpochMilli() - started.toEpochMilli();
            if (response.statusCode() != 200) {
                store.insertFetch(started, feedType, response.statusCode(), latency, null, null,
                        "HTTP " + response.statusCode());
                fail(feedType, "HTTP " + response.statusCode());
                return;
            }
            DecodedFeed decoded = GtfsRealtimeDecoder.decode(response.body(), started);
            if ("trip_updates".equals(feedType)) {
                store.applyTrips(decoded.feedTimestamp(), decoded.trips());
                long canceled = decoded.trips().stream().filter(DecodedFeed.TripSnapshot::canceled).count();
                log.info("Trip updates: {} entities, {} trips, {} canceled, feed {}",
                        decoded.entityCount(), decoded.trips().size(), canceled, decoded.feedTimestamp());
            } else {
                store.insertPositions(decoded.feedTimestamp(), decoded.vehicles());
                log.info("Positions: {} vehicles, feed {}", decoded.vehicles().size(), decoded.feedTimestamp());
            }
            store.insertFetch(started, feedType, 200, latency, decoded.entityCount(), decoded.feedTimestamp(), null);
            archive(feedType, decoded.feedTimestamp(), response.body());
            failures.put(feedType, 0);
            if (pingHealthcheck) {
                ping();
            }
        } catch (Exception e) {
            long latency = Instant.now().toEpochMilli() - started.toEpochMilli();
            String reason = rootMessage(e);
            store.insertFetch(started, feedType, null, latency, null, null, reason);
            fail(feedType, reason);
        }
    }

    private void archive(String feedType, Instant feedTimestamp, byte[] body) {
        Instant previous = lastArchive.get(feedType);
        if (previous != null && feedTimestamp.isBefore(previous.plus(props.getArchiveEvery()))) {
            return;
        }
        try {
            Path dir = Path.of(props.getArchiveDir(), feedType);
            Files.createDirectories(dir);
            String name = DateTimeFormatter.ISO_INSTANT.format(feedTimestamp).replace(":", "") + ".pb";
            Files.write(dir.resolve(name), body);
            lastArchive.put(feedType, feedTimestamp);
            int removed = ArchiveRetention.deleteOlderThan(
                    Path.of(props.getArchiveDir()), Instant.now().minus(props.getArchiveRetention()));
            if (removed > 0) {
                log.info("Removed {} archived feeds older than {}", removed, props.getArchiveRetention());
            }
        } catch (Exception e) {
            log.warn("Could not archive {} feed: {}", feedType, e.toString());
        }
    }

    private void ping() {
        if (props.getHealthcheckUrl() == null || props.getHealthcheckUrl().isBlank()) {
            return;
        }
        try {
            http.send(HttpRequest.newBuilder(URI.create(props.getHealthcheckUrl())).timeout(props.getHttpTimeout()).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("Healthchecks ping failed: {}", e.toString());
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName() + ": " + message;
    }

    private void fail(String feedType, String reason) {
        int n = failures.merge(feedType, 1, Integer::sum);
        log.warn("{} poll failed ({} in a row): {}", feedType, n, reason);
    }

    private void backoff(String feedType) {
        int n = failures.getOrDefault(feedType, 0);
        if (n == 0) return;
        long sleepMs = Math.min(300_000L, 1_000L << Math.min(n, 8));
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
