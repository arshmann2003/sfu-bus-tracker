package ca.sfu.bustracker.collector;

import ca.sfu.bustracker.GtfsTime;
import com.google.transit.realtime.GtfsRealtime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class GtfsRealtimeDecoder {
    private GtfsRealtimeDecoder() {}

    public static DecodedFeed decode(byte[] body, Instant fetchedAt) {
        try {
            GtfsRealtime.FeedMessage message = GtfsRealtime.FeedMessage.parseFrom(body);
            Instant feedTimestamp = message.getHeader().getTimestamp() > 0
                    ? Instant.ofEpochSecond(message.getHeader().getTimestamp())
                    : fetchedAt;
            List<DecodedFeed.TripSnapshot> trips = new ArrayList<>();
            List<DecodedFeed.VehicleFix> vehicles = new ArrayList<>();
            for (GtfsRealtime.FeedEntity entity : message.getEntityList()) {
                if (entity.hasTripUpdate()) {
                    trips.add(trip(entity.getTripUpdate()));
                }
                if (entity.hasVehicle()) {
                    DecodedFeed.VehicleFix fix = vehicle(entity.getVehicle());
                    if (fix != null) {
                        vehicles.add(fix);
                    }
                }
            }
            return new DecodedFeed(feedTimestamp, message.getEntityCount(), trips, vehicles);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not decode GTFS Realtime protobuf", e);
        }
    }

    private static DecodedFeed.TripSnapshot trip(GtfsRealtime.TripUpdate update) {
        GtfsRealtime.TripDescriptor descriptor = update.getTrip();
        String relationship = descriptor.getScheduleRelationship().name();
        boolean canceled = descriptor.getScheduleRelationship()
                == GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED;
        LocalDate serviceDate = GtfsTime.parseServiceDate(descriptor.getStartDate());
        List<DecodedFeed.StopPrediction> stops = new ArrayList<>();
        if (!canceled) {
            for (GtfsRealtime.TripUpdate.StopTimeUpdate stop : update.getStopTimeUpdateList()) {
                if (stop.getScheduleRelationship()
                        == GtfsRealtime.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED) {
                    continue;
                }
                Instant arrival = null;
                if (stop.hasArrival() && stop.getArrival().hasTime() && stop.getArrival().getTime() > 0) {
                    arrival = Instant.ofEpochSecond(stop.getArrival().getTime());
                }
                stops.add(new DecodedFeed.StopPrediction(stop.getStopSequence(), emptyToNull(stop.getStopId()), arrival));
            }
        }
        return new DecodedFeed.TripSnapshot(
                serviceDate,
                emptyToNull(descriptor.getTripId()),
                relationship,
                canceled,
                stops);
    }

    private static DecodedFeed.VehicleFix vehicle(GtfsRealtime.VehiclePosition position) {
        String vehicleId = position.hasVehicle() ? emptyToNull(position.getVehicle().getId()) : null;
        if (vehicleId == null) {
            return null;
        }
        GtfsRealtime.TripDescriptor trip = position.getTrip();
        Double lat = position.hasPosition() ? (double) position.getPosition().getLatitude() : null;
        Double lon = position.hasPosition() ? (double) position.getPosition().getLongitude() : null;
        Integer sequence = position.hasCurrentStopSequence() ? position.getCurrentStopSequence() : null;
        String status = position.hasCurrentStatus() ? position.getCurrentStatus().name() : null;
        return new DecodedFeed.VehicleFix(
                vehicleId,
                emptyToNull(trip.getTripId()),
                emptyToNull(trip.getRouteId()),
                GtfsTime.parseServiceDate(trip.getStartDate()),
                lat,
                lon,
                sequence,
                status);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
