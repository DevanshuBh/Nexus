package dev.nexus.writer;

import dev.nexus.common.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Parks records the writer cannot store, with the reason in headers, so an operator can inspect, fix and replay them.
 * Sends are awaited before the source offsets are committed: a row is either stored, in the DLQ, or redelivered.
 */
public final class DeadLetterPublisher implements AutoCloseable {

    public static final String H_ERROR_CLASS = "nexus.error.class";
    public static final String H_SQLSTATE = "nexus.error.sqlstate";
    public static final String H_MESSAGE = "nexus.error.message";
    public static final String H_TOPIC = "nexus.original.topic";
    public static final String H_PARTITION = "nexus.original.partition";
    public static final String H_OFFSET = "nexus.original.offset";

    public record DeadLetter(ConsumerRecord<String, byte[]> source, String errorClass, String sqlState, String message) {
    }

    private final KafkaProducer<String, byte[]> producer;
    private final String topic;

    public DeadLetterPublisher(String bootstrap, String topic) {
        this.topic = topic;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                ProducerConfig.CLIENT_ID_CONFIG, "nexus-writer-dlq"),
                new StringSerializer(), new ByteArraySerializer());
    }

    public DeadLetterPublisher(String bootstrap) {
        this(bootstrap, Topics.GPS_DLQ);
    }

    /** Blocks until every dead letter is acknowledged by the broker. */
    public void publishAll(List<DeadLetter> letters) throws ExecutionException, InterruptedException, TimeoutException {
        List<Future<RecordMetadata>> futures = new ArrayList<>(letters.size());
        for (DeadLetter d : letters) {
            var r = new ProducerRecord<>(topic, d.source().key(), d.source().value());
            r.headers()
                    .add(H_ERROR_CLASS, bytes(d.errorClass()))
                    .add(H_SQLSTATE, bytes(d.sqlState()))
                    .add(H_MESSAGE, bytes(d.message()))
                    .add(H_TOPIC, bytes(d.source().topic()))
                    .add(H_PARTITION, bytes(Integer.toString(d.source().partition())))
                    .add(H_OFFSET, bytes(Long.toString(d.source().offset())));
            futures.add(producer.send(r));
        }
        for (Future<RecordMetadata> f : futures) {
            f.get(35, TimeUnit.SECONDS);
        }
    }

    private static byte[] bytes(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        producer.close();
    }
}
