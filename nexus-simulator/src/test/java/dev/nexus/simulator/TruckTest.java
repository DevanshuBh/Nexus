package dev.nexus.simulator;

import dev.nexus.common.PingValidator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class TruckTest {

    @Test
    void producesValidPingsThatMoveWithinTheCity() {
        var t = new Truck("f01", "f01-v00001", 12.97, 77.59, 15, 0, false, new SplittableRandom(42));
        var validator = new PingValidator(Duration.ofSeconds(30), Duration.ofDays(7));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            var p = t.tick(now + i * 3000L, 0);
            assertThat(validator.validate(p, "f01-v00001", now + i * 3000L)).isEmpty();
            assertThat(Math.abs(p.lat() - 12.97)).isLessThan(0.2);
            assertThat(Math.abs(p.lon() - 77.59)).isLessThan(0.2);
        }
    }

    @Test
    void malformedPingsAreRejectedAtIngress() {
        var t = new Truck("f01", "f01-v00001", 12.97, 77.59, 15, 0, true, new SplittableRandom(7));
        var validator = new PingValidator(Duration.ofSeconds(30), Duration.ofDays(7));
        long now = System.currentTimeMillis();
        int invalid = 0;
        for (int i = 0; i < 50; i++) {
            if (validator.validate(t.tick(now, 1.0), "f01-v00001", now).isPresent()) {
                invalid++;
            }
        }
        assertThat(invalid).isEqualTo(50);
    }

    @Test
    void backlogKeepsOrderOnRequeue() {
        var t = new Truck("f01", "f01-v00001", 12.97, 77.59, 15, 0, false, new SplittableRandom(1));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            t.buffer(t.tick(now + i, 0));
        }
        var first = t.takeBatch(3);
        t.buffer(t.tick(now + 10, 0));
        t.requeue(first);
        var all = t.takeBatch(100);
        assertThat(all).hasSize(6);
        assertThat(all.getFirst().eventTs()).isEqualTo(now);
    }
}
