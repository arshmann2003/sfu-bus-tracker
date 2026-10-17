package ca.sfu.bustracker.collector;

import com.google.transit.realtime.GtfsRealtime;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GtfsRealtimeDecoderTest {

    @Test
    void readsCanceledTripStopPredictionAndVehicle() {
        byte[] body = GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(1_700_000_000L))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                        .setId("cancel")
                        .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                                        .setTripId("T-ghost")
                                        .setStartDate("20261016")
                                        .setScheduleRelationship(GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED))))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                        .setId("live")
                        .setTripUpdate(GtfsRealtime.TripUpdate.newBuilder()
                                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                                        .setTripId("T-145")
                                        .setStartDate("20261016"))
                                .addStopTimeUpdate(GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                                        .setStopSequence(3)
                                        .setStopId("SFU")
                                        .setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                                                .setTime(1_700_000_100L)))))
                .addEntity(GtfsRealtime.FeedEntity.newBuilder()
                        .setId("bus")
                        .setVehicle(GtfsRealtime.VehiclePosition.newBuilder()
                                .setVehicle(GtfsRealtime.VehicleDescriptor.newBuilder().setId("V1"))
                                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                                        .setTripId("T-145")
                                        .setRouteId("145")
                                        .setStartDate("20261016"))
                                .setPosition(GtfsRealtime.Position.newBuilder().setLatitude(49.28f).setLongitude(-122.92f))
                                .setCurrentStopSequence(4)
                                .setCurrentStatus(GtfsRealtime.VehiclePosition.VehicleStopStatus.IN_TRANSIT_TO)))
                .build()
                .toByteArray();

        DecodedFeed feed = GtfsRealtimeDecoder.decode(body, Instant.EPOCH);
        assertEquals(Instant.ofEpochSecond(1_700_000_000L), feed.feedTimestamp());
        assertEquals(2, feed.trips().size());
        assertTrue(feed.trips().get(0).canceled());
        assertEquals("T-145", feed.trips().get(1).tripId());
        assertEquals(Instant.ofEpochSecond(1_700_000_100L), feed.trips().get(1).stops().get(0).predictedArrival());
        assertEquals("V1", feed.vehicles().get(0).vehicleId());
        assertEquals(4, feed.vehicles().get(0).currentStopSequence());
        assertEquals("145", feed.vehicles().get(0).routeId());
    }
}
