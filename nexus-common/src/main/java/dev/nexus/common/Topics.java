package dev.nexus.common;

public final class Topics {

    /** Validated pings, key = vehicleId. */
    public static final String GPS = "nexus.gps.v1";
    /** Rows the writer could not store, with the reason in headers. */
    public static final String GPS_DLQ = "nexus.gps.dlq";

    /** Redis Pub/Sub channel carrying live positions for one fleet. */
    public static String positionChannel(String fleetId) {
        return "nexus:pos:" + fleetId;
    }

    public static final String POSITION_CHANNEL_PATTERN = "nexus:pos:*";

    /** Redis keys share the {fleet:ID} hash tag so the Lua guard touches one cluster slot. */
    public static String geoKey(String fleetId) {
        return "{fleet:" + fleetId + "}:geo";
    }

    public static String vehicleKey(String fleetId, String vehicleId) {
        return "{fleet:" + fleetId + "}:v:" + vehicleId;
    }

    private Topics() {
    }
}
