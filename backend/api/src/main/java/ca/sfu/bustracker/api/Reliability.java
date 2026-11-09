package ca.sfu.bustracker.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scoring rules from MINIPLAN. Frequent hours use wait and spacing.
 * Scheduled hours use the on-time window. A route can be both, depending on the hour.
 */
public final class Reliability {
    public static final int ON_TIME_EARLY_SEC = 60;
    public static final int ON_TIME_LATE_SEC = 180;
    public static final int FREQUENT_HEADWAY_SEC = 600;
    /** Gaps longer than this are a break in service, not a headway. */
    public static final int BREAK_GAP_SEC = 20 * 60;

    private Reliability() {}

    public static boolean onTime(long delaySec) {
        return delaySec >= -ON_TIME_EARLY_SEC && delaySec <= ON_TIME_LATE_SEC;
    }

    public static boolean frequent(long headwaySec) {
        return headwaySec > 0 && headwaySec <= FREQUENT_HEADWAY_SEC;
    }

    /** Median gap between sorted trip starts, ignoring service breaks. */
    public static Long operatingHeadway(List<Long> startSecs) {
        if (startSecs.size() < 2) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(startSecs);
        Collections.sort(sorted);
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            long gap = sorted.get(i) - sorted.get(i - 1);
            if (gap > 0 && gap <= BREAK_GAP_SEC) {
                gaps.add(gap);
            }
        }
        return median(gaps);
    }

    /**
     * Expected wait for a rider who shows up at a random moment: sum(gap²) / (2 · sum(gap)).
     * For a perfectly even headway H this is H / 2.
     */
    public static Double averageWait(List<Long> gaps) {
        long sum = 0;
        double sumSq = 0;
        for (long gap : gaps) {
            if (gap <= 0) {
                continue;
            }
            sum += gap;
            sumSq += (double) gap * gap;
        }
        if (sum == 0) {
            return null;
        }
        return sumSq / (2.0 * sum);
    }

    public static Double excessWait(List<Long> gaps, long scheduledHeadwaySec) {
        Double wait = averageWait(gaps);
        if (wait == null || scheduledHeadwaySec <= 0) {
            return null;
        }
        return wait - scheduledHeadwaySec / 2.0;
    }

    /** Share of gaps within ±50% of the scheduled headway. */
    public static Double regularity(List<Long> gaps, long scheduledHeadwaySec) {
        if (scheduledHeadwaySec <= 0) {
            return null;
        }
        int counted = 0;
        int regular = 0;
        double low = scheduledHeadwaySec * 0.5;
        double high = scheduledHeadwaySec * 1.5;
        for (long gap : gaps) {
            if (gap <= 0) {
                continue;
            }
            counted++;
            if (gap >= low && gap <= high) {
                regular++;
            }
        }
        if (counted == 0) {
            return null;
        }
        return 100.0 * regular / counted;
    }

    public static int bunching(List<Long> gaps, long scheduledHeadwaySec) {
        if (scheduledHeadwaySec <= 0) {
            return 0;
        }
        int count = 0;
        double limit = scheduledHeadwaySec * 0.25;
        for (long gap : gaps) {
            if (gap > 0 && gap < limit) {
                count++;
            }
        }
        return count;
    }

    public static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
