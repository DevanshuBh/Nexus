package dev.nexus.ingest;

import dev.nexus.common.Ping;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryPublisherTest {

    static Ping ping(String vehicle) {
        return new Ping(UUID.randomUUID().toString(), vehicle, "f01", 1L, 1.0, 1.0, 1.0, 1.0, 1L);
    }

    @Test
    void answersOnlyAfterBrokerAck() throws Exception {
        var mock = new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer()); // manual completion
        var pub = new TelemetryPublisher(mock, "t", Duration.ofSeconds(5));

        var result = CompletableFuture.supplyAsync(() -> pub.publishAndAwait(List.of(ping("v1"), ping("v2"))));
        Thread.sleep(200);
        assertThat(result).as("must not answer while records are only in the producer buffer").isNotDone();

        mock.completeNext();
        mock.errorNext(new RuntimeException("broker down"));
        assertThat(result.get()).containsExactly(true, false);
        assertThat(mock.history()).extracting(r -> r.key()).containsExactly("v1", "v2"); // key = vehicleId
    }

    @Test
    void unacknowledgedWithinTimeoutIsRetry() {
        var mock = new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
        var pub = new TelemetryPublisher(mock, "t", Duration.ofMillis(100));
        assertThat(pub.publishAndAwait(List.of(ping("v1")))).containsExactly(false);
    }
}
