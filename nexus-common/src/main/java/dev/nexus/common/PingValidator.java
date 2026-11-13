package dev.nexus.common;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Ingress validation. Rejecting bad data here is far cheaper than isolating it in the writer by bisection.
 *
 * <p>Event time may be in the past by up to {@code maxAge} (devices buffer while offline) but only slightly
 * in the future ({@code maxFutureSkew}) to tolerate clock drift.
 */
public final class PingValidator {

    private static final int MAX_ID_LENGTH = 64;

    private final long maxFutureSkewMs;
    private final long maxAgeMs;

    public PingValidator(Duration maxFutureSkew, Duration maxAge) {
        this.maxFutureSkewMs = maxFutureSkew.toMillis();
        this.maxAgeMs = maxAge.toMillis();
    }

    /** @return empty if valid, otherwise a short machine-readable reason */
    public Optional<String> validate(Ping p, String authenticatedDeviceId, long nowMillis) {
        if (p == null) {
            return Optional.of("null_ping");
        }
        if (p.eventId() == null || p.eventId().isBlank()) {
            return Optional.of("missing_event_id");
        }
        if (!isUuid(p.eventId())) {
            return Optional.of("invalid_event_id");
        }
        if (p.vehicleId() == null || p.vehicleId().isBlank()) {
            return Optional.of("missing_vehicle_id");
        }
        if (p.vehicleId().length() > MAX_ID_LENGTH || !isSafeId(p.vehicleId())) {
            return Optional.of("invalid_vehicle_id");
        }
        if (authenticatedDeviceId != null && !authenticatedDeviceId.equals(p.vehicleId())) {
            // A device may only report its own position.
            return Optional.of("vehicle_device_mismatch");
        }
        if (p.eventTs() == null) {
            return Optional.of("missing_event_ts");
        }
        if (p.eventTs() > nowMillis + maxFutureSkewMs) {
            return Optional.of("event_ts_in_future");
        }
        if (p.eventTs() < nowMillis - maxAgeMs) {
            return Optional.of("event_ts_too_old");
        }
        if (p.lat() == null || p.lon() == null) {
            return Optional.of("missing_coordinates");
        }
        if (!Double.isFinite(p.lat()) || p.lat() < -90 || p.lat() > 90) {
            return Optional.of("lat_out_of_range");
        }
        if (!Double.isFinite(p.lon()) || p.lon() < -180 || p.lon() > 180) {
            return Optional.of("lon_out_of_range");
        }
        if (p.speedKph() == null || !Double.isFinite(p.speedKph()) || p.speedKph() < 0 || p.speedKph() > 400) {
            return Optional.of("speed_out_of_range");
        }
        if (p.heading() == null || !Double.isFinite(p.heading()) || p.heading() < 0 || p.heading() > 360) {
            return Optional.of("heading_out_of_range");
        }
        return Optional.empty();
    }

    /** Ids go into Redis keys, CSV and logs: allow only a conservative character set. */
    public static boolean isSafeId(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
