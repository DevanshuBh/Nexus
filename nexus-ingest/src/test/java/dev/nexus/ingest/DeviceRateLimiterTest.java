package dev.nexus.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRateLimiterTest {

    @Test
    void hotDeviceIsThrottledOthersAreNot() {
        var rl = new DeviceRateLimiter(1.0); // 1 ping/s, burst 30
        long t = 0;
        int passed = 0;
        for (int i = 0; i < 100; i++) { // hot device: 100 pings at once
            if (rl.tryAcquire("f01/hot", 1, t)) {
                passed++;
            }
        }
        assertThat(passed).isEqualTo(30);
        assertThat(rl.tryAcquire("f01/normal", 1, t)).isTrue();
        // refills over time
        assertThat(rl.tryAcquire("f01/hot", 1, t + 2_000_000_000L)).isTrue();
    }

    @Test
    void refundAfterServerFailureRestoresBudget() {
        var rl = new DeviceRateLimiter(1.0);
        assertThat(rl.tryAcquire("f01/v1", 30, 0)).isTrue();
        assertThat(rl.tryAcquire("f01/v1", 30, 0)).isFalse();
        rl.refund("f01/v1", 30); // that request got a 503
        assertThat(rl.tryAcquire("f01/v1", 30, 0)).isTrue();
    }
}
