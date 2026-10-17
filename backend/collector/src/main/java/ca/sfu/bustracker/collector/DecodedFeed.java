package ca.sfu.bustracker.collector;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DecodedFeed(
        Instant feedTimestamp,
        int entityCount,
        List<TripSnapshot> trips,
        List<VehicleFix> vehicles
) {
    public record TripSnapshot(
            LocalDate serviceDate,
            String tripId,
            String relationship,
            boolean canceled,
            List<StopPrediction> stops
    ) {}

    public record StopPrediction(
            int stopSequence,
            String stopId,
            Instant predictedArrival
    ) {}

    public record VehicleFix(
            String vehicleId,
            String tripId,
            String routeId,
            LocalDate serviceDate,
            Double lat,
            Double lon,
            Integer currentStopSequence,
            String currentStatus
    ) {}
}
