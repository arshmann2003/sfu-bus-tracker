package ca.sfu.bustracker.collector;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealtimeStoreTest {

    @Test
    void laterPredictionReplacesARepeatedStop() {
        LocalDate day = LocalDate.of(2026, 11, 9);
        Timestamp seen = Timestamp.from(Instant.parse("2026-11-09T19:44:30Z"));
        List<DecodedFeed.TripSnapshot> trips = List.of(
                new DecodedFeed.TripSnapshot(day, "T-145", "SCHEDULED", false, List.of(
                        new DecodedFeed.StopPrediction(3, "FIRST", Instant.parse("2026-11-09T20:00:00Z")),
                        new DecodedFeed.StopPrediction(3, "SECOND", Instant.parse("2026-11-09T20:01:00Z")),
                        new DecodedFeed.StopPrediction(4, "NEXT", Instant.parse("2026-11-09T20:10:00Z"))
                )),
                new DecodedFeed.TripSnapshot(day, "T-145", "SCHEDULED", false, List.of(
                        new DecodedFeed.StopPrediction(3, "LATER", Instant.parse("2026-11-09T20:02:00Z"))
                )),
                new DecodedFeed.TripSnapshot(day, "T-cancel", "CANCELED", true, List.of())
        );

        List<Object[]> rows = RealtimeStore.collapseStops(trips, seen);

        assertEquals(2, rows.size());
        assertEquals("LATER", rows.get(0)[3]);
        assertEquals(Timestamp.from(Instant.parse("2026-11-09T20:02:00Z")), rows.get(0)[4]);
        assertEquals(4, rows.get(1)[2]);
        assertEquals("NEXT", rows.get(1)[3]);
    }
}
