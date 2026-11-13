package dev.nexus.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PingValidatorTest {

    private static final long NOW = 1_800_000_000_000L;
    private final PingValidator v = new PingValidator(Duration.ofSeconds(30), Duration.ofDays(7));

    private static Ping ping(Long ts, Double lat, Double lon) {
        return new Ping(UUID.randomUUID().toString(), "v0001", null, ts, lat, lon, 50.0, 90.0, 0);
    }

    @Test
    void acceptsValid() {
        assertThat(v.validate(ping(NOW, 12.97, 77.59), "v0001", NOW)).isEmpty();
    }

    @Test
    void rejectsMissingAndOutOfRange() {
        assertThat(v.validate(ping(NOW, null, 77.0), "v0001", NOW)).contains("missing_coordinates");
        assertThat(v.validate(ping(NOW, 91.0, 77.0), "v0001", NOW)).contains("lat_out_of_range");
        assertThat(v.validate(ping(NOW, 12.0, -181.0), "v0001", NOW)).contains("lon_out_of_range");
        assertThat(v.validate(ping(NOW, Double.NaN, 77.0), "v0001", NOW)).contains("lat_out_of_range");
        assertThat(v.validate(ping(null, 12.0, 77.0), "v0001", NOW)).contains("missing_event_ts");
    }

    @Test
    void timestampWindow() {
        assertThat(v.validate(ping(NOW + 31_000, 12.0, 77.0), "v0001", NOW)).contains("event_ts_in_future");
        assertThat(v.validate(ping(NOW - Duration.ofDays(8).toMillis(), 12.0, 77.0), "v0001", NOW)).contains("event_ts_too_old");
        // an offline device uploading yesterday's backlog is fine
        assertThat(v.validate(ping(NOW - Duration.ofDays(1).toMillis(), 12.0, 77.0), "v0001", NOW)).isEmpty();
    }

    @Test
    void deviceCanOnlyReportItself() {
        assertThat(v.validate(ping(NOW, 12.0, 77.0), "v0002", NOW)).contains("vehicle_device_mismatch");
    }

    @Test
    void rejectsUnsafeIds() {
        Ping p = new Ping(UUID.randomUUID().toString(), "v1;DROP", null, NOW, 1.0, 1.0, 1.0, 1.0, 0);
        assertThat(v.validate(p, null, NOW)).contains("invalid_vehicle_id");
        Ping q = new Ping("not-a-uuid", "v1", null, NOW, 1.0, 1.0, 1.0, 1.0, 0);
        assertThat(v.validate(q, null, NOW)).contains("invalid_event_id");
    }
}
