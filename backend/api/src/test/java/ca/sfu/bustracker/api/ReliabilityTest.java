package ca.sfu.bustracker.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityTest {

    @Test
    void onTimeIsEarlyByOneMinuteThroughLateByThree() {
        assertTrue(Reliability.onTime(-60));
        assertTrue(Reliability.onTime(0));
        assertTrue(Reliability.onTime(180));
        assertFalse(Reliability.onTime(-61));
        assertFalse(Reliability.onTime(181));
    }

    @Test
    void frequentMeansAScheduledGapOfTenMinutesOrLess() {
        assertTrue(Reliability.frequent(600));
        assertFalse(Reliability.frequent(601));
    }

    @Test
    void evenHeadwayHasNoExcessWait() {
        List<Long> gaps = List.of(600L, 600L, 600L);
        assertEquals(300.0, Reliability.averageWait(gaps), 0.001);
        assertEquals(0.0, Reliability.excessWait(gaps, 600), 0.001);
        assertEquals(100.0, Reliability.regularity(gaps, 600), 0.001);
        assertEquals(0, Reliability.bunching(gaps, 600));
    }

    @Test
    void aShortGapCountsAsBunching() {
        assertEquals(1, Reliability.bunching(List.of(100L, 600L), 600));
    }

    @Test
    void operatingHeadwayIgnoresTheBreakBetweenPeaks() {
        Long headway = Reliability.operatingHeadway(List.of(8 * 3600L, 8 * 3600L + 480, 18 * 3600L));
        assertEquals(480L, headway);
    }

    @Test
    void emptyGapsHaveNoWait() {
        assertNull(Reliability.averageWait(List.of()));
        assertNull(Reliability.excessWait(List.of(), 600));
    }
}
