package dev.nexus.gateway;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

final class GatewayMetrics {

    final AtomicInteger connections = new AtomicInteger();
    final Counter messagesIn;
    final Counter frames;
    final Counter positionsOut;
    final Counter coalesced;
    final Counter dropped;
    final Counter deferred;
    final Counter authFailures;

    GatewayMetrics(MeterRegistry r) {
        r.gauge("nexus_gateway_connections", connections);
        messagesIn = Counter.builder("nexus_gateway_messages_in").description("Positions received from Redis").register(r);
        frames = Counter.builder("nexus_gateway_frames_out").description("WebSocket frames sent").register(r);
        positionsOut = Counter.builder("nexus_gateway_positions_out").register(r);
        coalesced = Counter.builder("nexus_gateway_coalesced").description("Positions replaced by a newer one before sending").register(r);
        dropped = Counter.builder("nexus_gateway_dropped").description("Positions dropped: client buffer full").register(r);
        deferred = Counter.builder("nexus_gateway_deferred_flushes").description("Flushes skipped: client not writable (slow)").register(r);
        authFailures = Counter.builder("nexus_gateway_auth_failures").register(r);
    }
}
