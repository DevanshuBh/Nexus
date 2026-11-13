package dev.nexus.ingest;

import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Publishes validated pings and reports, per ping, whether the broker acknowledged it.
 *
 * <p>The API answers only after this returns: with {@code acks=all} an "accepted" ping is on every in-sync replica.
 * Answering earlier, while records sit in the producer's memory buffer, would let a crash lose pings the device
 * believes were stored.
 *
 * <p>Backpressure: {@code max.block.ms} is short, so when Kafka is down or slow and the 32 MB buffer fills, send()
 * fails fast instead of blocking request threads; the controller turns that into 503 + Retry-After.
 */
public class TelemetryPublisher implements AutoCloseable {

    private final Producer<String, byte[]> producer;
    private final String topic;
    private final Duration ackTimeout;

    public TelemetryPublisher(IngestProperties props) {
        this(new KafkaProducer<>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafkaBootstrap(),
                        ProducerConfig.ACKS_CONFIG, "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                        ProducerConfig.LINGER_MS_CONFIG, 5,
                        ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4",
                        ProducerConfig.BUFFER_MEMORY_CONFIG, 32L * 1024 * 1024,
                        ProducerConfig.MAX_BLOCK_MS_CONFIG, 250,
                        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) props.ackTimeout().toMillis() + 1_000,
                        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) props.ackTimeout().toMillis(),
                        ProducerConfig.CLIENT_ID_CONFIG, "nexus-ingest"),
                        new StringSerializer(), new ByteArraySerializer()),
                props.topic(), props.ackTimeout());
    }

    TelemetryPublisher(Producer<String, byte[]> producer, String topic, Duration ackTimeout) {
        this.producer = producer;
        this.topic = topic;
        this.ackTimeout = ackTimeout;
    }

    /** @return one entry per input ping, in order: true if acknowledged by the broker */
    public List<Boolean> publishAndAwait(List<Ping> pings) {
        List<Future<RecordMetadata>> futures = new ArrayList<>(pings.size());
        for (Ping p : pings) {
            try {
                futures.add(producer.send(new ProducerRecord<>(topic, p.vehicleId(), Json.write(p))));
            } catch (RuntimeException e) {
                // Buffer full (max.block.ms elapsed) or producer closed: fail this ping, the device retries it.
                futures.add(CompletableFuture.failedFuture(e));
            }
        }
        long deadline = System.nanoTime() + ackTimeout.toNanos();
        List<Boolean> acked = new ArrayList<>(pings.size());
        for (Future<RecordMetadata> f : futures) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                f.get(remaining, TimeUnit.NANOSECONDS);
                acked.add(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acked.add(false);
            } catch (Exception e) {
                acked.add(false);
            }
        }
        return acked;
    }

    @Override
    public void close() {
        producer.close(Duration.ofSeconds(5));
    }
}
