package ca.sfu.bustracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GtfsTimeTest {

    @Test
    void pastMidnightStaysOnTheServiceDay() {
        assertEquals(25 * 3600 + 15 * 60, GtfsTime.parseSeconds("25:15:00"));
    }

    @Test
    void blankIsMissing() {
        assertNull(GtfsTime.parseSeconds(null));
        assertNull(GtfsTime.parseSeconds("  "));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> GtfsTime.parseSeconds("8am"));
        assertThrows(IllegalArgumentException.class, () -> GtfsTime.parseSeconds("10:75:00"));
    }

    @Test
    void serviceDate() {
        assertEquals(LocalDate.of(2026, 10, 16), GtfsTime.parseServiceDate("20261016"));
        assertNull(GtfsTime.parseServiceDate(""));
    }
}
