package dev.nexus.common;

/** Last known position of a vehicle, as published to the live map. */
public record Position(String vehicleId, String fleetId, long ts, double lat, double lon, double speedKph, double heading) {

    public static Position of(Ping p) {
        return new Position(p.vehicleId(), p.fleetId(), p.eventTs(), p.lat(), p.lon(), p.speedKph(), p.heading());
    }
}
