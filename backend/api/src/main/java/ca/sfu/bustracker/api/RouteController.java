package ca.sfu.bustracker.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
public class RouteController {
    private final RouteService routes;

    public RouteController(RouteService routes) {
        this.routes = routes;
    }

    @GetMapping("/api/status")
    public ResponseEntity<RouteService.StatusView> status() {
        return json(routes.status());
    }

    @GetMapping("/api/routes")
    public ResponseEntity<List<RouteService.RouteSummary>> routes(
            @RequestParam(defaultValue = "sfu") String focus,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to
    ) {
        if (!"sfu".equals(focus)) {
            return json(List.of());
        }
        DateRange range = range(from, to);
        return json(routes.routes(range.from(), range.to()));
    }

    @GetMapping("/api/routes/{id}/stats")
    public ResponseEntity<RouteService.RouteSummary> stats(
            @PathVariable String id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer direction
    ) {
        DateRange range = range(from, to);
        return json(routes.report(id, range.from(), range.to(), direction).summary());
    }

    @GetMapping("/api/routes/{id}/hourly")
    public ResponseEntity<List<RouteService.HourStat>> hourly(
            @PathVariable String id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer direction
    ) {
        DateRange range = range(from, to);
        return json(routes.report(id, range.from(), range.to(), direction).hourly());
    }

    @GetMapping("/api/routes/{id}/stops")
    public ResponseEntity<List<RouteService.StopStat>> stops(
            @PathVariable String id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer direction
    ) {
        DateRange range = range(from, to);
        return json(routes.report(id, range.from(), range.to(), direction).stops());
    }

    @GetMapping("/api/routes/{id}")
    public ResponseEntity<RouteService.RouteReport> report(
            @PathVariable String id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer direction
    ) {
        DateRange range = range(from, to);
        return json(routes.report(id, range.from(), range.to(), direction));
    }

    private DateRange range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? routes.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        return new DateRange(start, end);
    }

    private static <T> ResponseEntity<T> json(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30))).body(body);
    }

    private record DateRange(LocalDate from, LocalDate to) {}
}
