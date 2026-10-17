package ca.sfu.bustracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * GTFS clock times are seconds since service-day start, not wall-clock times.
 * A trip that runs past midnight is stored as {@code 25:15:00}, not {@code 01:15:00}.
 */
public final class GtfsTime {
    private static final DateTimeFormatter SERVICE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private GtfsTime() {}

    public static Integer parseSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Not a GTFS time: " + raw);
        }
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        int seconds = Integer.parseInt(parts[2]);
        if (minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59 || hours < 0) {
            throw new IllegalArgumentException("Not a GTFS time: " + raw);
        }
        return hours * 3600 + minutes * 60 + seconds;
    }
    public static LocalDate parseServiceDate(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.isBlank()) {
            return null;
        }
        return LocalDate.parse(yyyymmdd.trim(), SERVICE_DATE);
    }
}
