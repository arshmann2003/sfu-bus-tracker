package ca.sfu.bustracker.api;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteService {
    private static final int EARLY_DAYS = 10;

    private final StatsRepository repo;
    private final ZoneId zone;

    public RouteService(StatsRepository repo) {
        this.repo = repo;
        this.zone = ZoneId.of("America/Vancouver");
    }

    public List<RouteSummary> routes(LocalDate from, LocalDate to) {
        long feed = repo.latestFeed();
        Map<String, RouteMeta> routes = loadRoutes(feed);
        List<RouteSummary> summaries = new ArrayList<>();
        for (String name : StatsRepository.SFU_ROUTES) {
            RouteMeta meta = routes.get(name);
            if (meta == null) {
                continue;
            }
            summaries.add(summarize(feed, meta, from, to, -1));
        }
        return summaries;
    }

    public RouteReport report(String route, LocalDate from, LocalDate to, Integer direction) {
        long feed = repo.latestFeed();
        RouteMeta meta = loadRoutes(feed).get(route);
        if (meta == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown route " + route);
        }
        int dir = direction == null ? -1 : direction;
        List<StatsRepository.HourDelay> delays = repo.delays(feed, route, from, to, dir);
        List<StatsRepository.HourGap> gaps = repo.gaps(feed, route, from, to, dir);
        RouteSummary summary = summarize(feed, meta, from, to, dir, delays, gaps);
        List<HourStat> hourly = hourly(meta, delays, gaps);
        List<StopStat> stops = repo.stops(feed, route, from, to, dir).stream()
                .map(stop -> new StopStat(stop.stopId(), stop.name(), stop.sequence(),
                        round1(stop.avgDelaySec()), stop.observations()))
                .toList();
        List<DirectionChoice> directions = repo.directions(feed, route).stream()
                .map(row -> new DirectionChoice(row.directionId(), row.headsign()))
                .distinct()
                .toList();
        return new RouteReport(summary, hourly, stops, directions);
    }

    public StatusView status() {
        StatsRepository.StatusRow row = repo.status();
        long age = row.lastFetch() == null ? -1 : Instant.now().getEpochSecond() - row.lastFetch().getEpochSecond();
        Double coverage = row.fetches() == 0 ? null : round1(100.0 * row.okFetches() / row.fetches());
        return new StatusView(row.lastFetch(), age, row.days(), row.arrivals(), row.observed(), coverage, row.days() < EARLY_DAYS);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    private RouteSummary summarize(long feed, RouteMeta meta, LocalDate from, LocalDate to, int direction) {
        List<StatsRepository.HourDelay> delays = repo.delays(feed, meta.shortName(), from, to, direction);
        List<StatsRepository.HourGap> gaps = repo.gaps(feed, meta.shortName(), from, to, direction);
        return summarize(feed, meta, from, to, direction, delays, gaps);
    }

    private RouteSummary summarize(long feed, RouteMeta meta, LocalDate from, LocalDate to, int direction,
                                   List<StatsRepository.HourDelay> delays, List<StatsRepository.HourGap> gaps) {
        Long headway = Reliability.operatingHeadway(meta.starts());
        boolean frequent = headway != null && Reliability.frequent(headway);
        int observations = delays.stream().mapToInt(StatsRepository.HourDelay::observations).sum();
        int onTime = delays.stream().mapToInt(StatsRepository.HourDelay::onTime).sum();
        double delaySum = delays.stream().mapToDouble(d -> d.avgDelaySec() * d.observations()).sum();
        List<Long> gapSecs = gaps.stream().map(StatsRepository.HourGap::gapSec).toList();
        int days = repo.days(feed, meta.shortName(), from, to);
        int cancelled = repo.cancelled(feed, meta.shortName(), from, to, direction);
        int bunching = headway == null ? 0 : Reliability.bunching(gapSecs, headway);
        Double onTimePct = frequent || observations == 0 ? null : round1(100.0 * onTime / observations);
        Double avgDelay = frequent || observations == 0 ? null : round1(delaySum / observations);
        Double excess = frequent ? round1(Reliability.excessWait(gapSecs, headway)) : null;
        Double regularity = frequent ? round1(Reliability.regularity(gapSecs, headway)) : null;
        return new RouteSummary(
                meta.shortName(), meta.longName(), frequent,
                onTimePct, avgDelay, excess, regularity,
                observations, cancelled, bunching, days, days < EARLY_DAYS
        );
    }

    private List<HourStat> hourly(RouteMeta meta, List<StatsRepository.HourDelay> delays, List<StatsRepository.HourGap> gaps) {
        Map<Integer, List<Long>> startsByHour = new LinkedHashMap<>();
        for (long start : meta.starts()) {
            int hour = (int) ((start / 3600) % 24);
            startsByHour.computeIfAbsent(hour, key -> new ArrayList<>()).add(start);
        }
        Map<Integer, StatsRepository.HourDelay> delayByHour = new LinkedHashMap<>();
        for (StatsRepository.HourDelay delay : delays) {
            delayByHour.put(delay.hour(), delay);
        }
        Map<Integer, List<Long>> gapsByHour = new LinkedHashMap<>();
        for (StatsRepository.HourGap gap : gaps) {
            gapsByHour.computeIfAbsent(gap.hour(), key -> new ArrayList<>()).add(gap.gapSec());
        }
        List<HourStat> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            Long headway = Reliability.operatingHeadway(startsByHour.getOrDefault(hour, List.of()));
            boolean frequent = headway != null && Reliability.frequent(headway);
            StatsRepository.HourDelay delay = delayByHour.get(hour);
            List<Long> hourGaps = gapsByHour.getOrDefault(hour, List.of());
            int observations = delay == null ? 0 : delay.observations();
            if (observations == 0 && hourGaps.isEmpty()) {
                continue;
            }
            Double onTimePct = null;
            Double avgDelay = null;
            Double excess = null;
            Double regularity = null;
            if (frequent) {
                excess = round1(Reliability.excessWait(hourGaps, headway));
                regularity = round1(Reliability.regularity(hourGaps, headway));
            } else if (delay != null && observations > 0) {
                onTimePct = round1(100.0 * delay.onTime() / observations);
                avgDelay = round1(delay.avgDelaySec());
            }
            hours.add(new HourStat(hour, frequent ? "frequent" : "scheduled", observations,
                    onTimePct, avgDelay, excess, regularity));
        }
        return hours;
    }

    private Map<String, RouteMeta> loadRoutes(long feed) {
        Map<String, RouteMeta> routes = new LinkedHashMap<>();
        for (StatsRepository.RouteRow row : repo.routes(feed)) {
            routes.computeIfAbsent(row.shortName(), name -> new RouteMeta(name, row.longName(), new ArrayList<>()))
                    .starts()
                    .add(row.startSecs());
        }
        return routes;
    }

    private static Double round1(Double value) {
        if (value == null || value.isNaN()) {
            return null;
        }
        return Math.round(value * 10.0) / 10.0;
    }

    private record RouteMeta(String shortName, String longName, List<Long> starts) {}

    public record RouteSummary(
            String id,
            String longName,
            boolean frequent,
            Double onTimePct,
            Double avgDelaySec,
            Double excessWaitSec,
            Double regularityPct,
            int observations,
            int cancelled,
            int bunching,
            int days,
            boolean earlyData
    ) {}

    public record HourStat(
            int hour,
            String type,
            int observations,
            Double onTimePct,
            Double avgDelaySec,
            Double excessWaitSec,
            Double regularityPct
    ) {}

    public record StopStat(String stopId, String name, int sequence, Double avgDelaySec, int observations) {}

    public record DirectionChoice(int directionId, String headsign) {}

    public record RouteReport(
            RouteSummary summary,
            List<HourStat> hourly,
            List<StopStat> stops,
            List<DirectionChoice> directions
    ) {}

    public record StatusView(
            Instant lastFetch,
            long feedAgeSeconds,
            int daysCollected,
            long arrivals,
            long observedArrivals,
            Double coveragePct,
            boolean earlyData
    ) {}
}
