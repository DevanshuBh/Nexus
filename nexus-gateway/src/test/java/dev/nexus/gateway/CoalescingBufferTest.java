package dev.nexus.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoalescingBufferTest {

    @Test
    void keepsOnlyNewestPerVehicle() {
        var b = new CoalescingBuffer(100);
        for (int i = 0; i < 1_000; i++) {
            b.offer("v1", "{\"n\":" + i + "}");
        }
        b.offer("v2", "x");
        var drained = b.drain();
        assertThat(drained).hasSize(2);
        assertThat(drained.getFirst().json()).isEqualTo("{\"n\":999}");
        assertThat(b.takeCoalesced()).isEqualTo(999);
        assertThat(b.drain()).isEmpty();
    }

    @Test
    void slowClientMemoryIsBoundedByCapacity() {
        // A client that never drains: memory is capped at capacity entries no matter how many pings arrive.
        var b = new CoalescingBuffer(50);
        for (int round = 0; round < 100; round++) {
            for (int v = 0; v < 80; v++) {
                b.offer("v" + v, "p" + round);
            }
        }
        assertThat(b.size()).isEqualTo(50);
        assertThat(b.takeDropped()).isEqualTo(100L * 30);
    }
}
