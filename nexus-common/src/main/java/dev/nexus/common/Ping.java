package dev.nexus.common;

/**
 * One GPS reading from one vehicle.
 *
 * <p>Fields are boxed on purpose: a device that leaves out {@code lat} must be rejected
 * with a reason, not silently stored at latitude 0. {@link PingValidator} checks every field.
 *
 * @param eventId    device-generated unique id (UUID); part of the dedup key
 * @param vehicleId  the vehicle; also the Kafka key, so one truck's pings stay in order
 * @param fleetId    tenant; set by the ingest API from the authenticated device, never trusted from the body
 * @param eventTs    device clock, epoch milliseconds
 * @param lat        WGS84 latitude, -90..90
 * @param lon        WGS84 longitude, -180..180
 * @param speedKph   0..400
 * @param heading    degrees 0..360
 * @param receivedAt server clock at ingest, epoch milliseconds (0 until the ingest API sets it)
 */
public record Ping(
        String eventId,
        String vehicleId,
        String fleetId,
        Long eventTs,
        Double lat,
        Double lon,
        Double speedKph,
        Double heading,
        long receivedAt) {

    public Ping withFleetAndReceivedAt(String fleet, long receivedAtMillis) {
        return new Ping(eventId, vehicleId, fleet, eventTs, lat, lon, speedKph, heading, receivedAtMillis);
    }
}
