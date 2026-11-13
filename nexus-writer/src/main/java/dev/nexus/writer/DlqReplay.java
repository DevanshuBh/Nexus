package dev.nexus.writer;

import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import dev.nexus.common.PingValidator;
import dev.nexus.common.Topics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator CLI for the dead letter topic.
 *
 * <pre>
 *   java -cp nexus-writer.jar dev.nexus.writer.DlqReplay                 # list (dry run, default)
 *   java -cp nexus-writer.jar dev.nexus.writer.DlqReplay --republish     # republish records that are now valid
 *   java -cp nexus-writer.jar dev.nexus.writer.DlqReplay --republish --fix-heading   # wrap heading into 0..360 first
 * </pre>
 * Republishing is safe to repeat: the writer stores each event at most once (dedup index), and this tool commits its
 * own consumer-group offsets so a second run starts after the last replayed record.
 */
public final class DlqReplay {

    public static void main(String[] args) {
        boolean republish = List.of(args).contains("--republish");
        boolean fixHeading = List.of(args).contains("--fix-heading");
        String bootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
        PingValidator validator = new PingValidator(Duration.ofMinutes(5), Duration.ofDays(30));

        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "nexus-dlq-replay",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer());
             var producer = new KafkaProducer<String, byte[]>(Map.of(
                     ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                     ProducerConfig.ACKS_CONFIG, "all",
                     ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                     new StringSerializer(), new ByteArraySerializer())) {

            consumer.subscribe(List.of(Topics.GPS_DLQ));
            int seen = 0, replayed = 0, skipped = 0, emptyPolls = 0;
            while (emptyPolls < 5) {
                var records = consumer.poll(Duration.ofSeconds(1));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
                for (ConsumerRecord<String, byte[]> r : records) {
                    seen++;
                    String reason = header(r, DeadLetterPublisher.H_ERROR_CLASS) + " " + header(r, DeadLetterPublisher.H_SQLSTATE)
                            + " " + header(r, DeadLetterPublisher.H_MESSAGE);
                    Ping p = tryParse(r.value());
                    if (p != null && fixHeading && p.heading() != null) {
                        double h = ((p.heading() % 360) + 360) % 360;
                        p = new Ping(p.eventId(), p.vehicleId(), p.fleetId(), p.eventTs(), p.lat(), p.lon(), p.speedKph(), h, p.receivedAt());
                    }
                    String invalid = p == null ? "unparseable" : validator.validate(p, null, System.currentTimeMillis()).orElse(null);
                    System.out.printf("%s-%d@%d key=%s reason=[%s] -> %s%n", r.topic(), r.partition(), r.offset(), r.key(),
                            reason.trim(), invalid == null ? (republish ? "REPUBLISH" : "would republish") : "skip (" + invalid + ")");
                    if (invalid == null && republish) {
                        producer.send(new ProducerRecord<>(Topics.GPS, p.vehicleId(), Json.write(p))).get();
                        replayed++;
                    } else if (invalid != null) {
                        skipped++;
                    }
                    offsets.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                }
                if (republish) {
                    consumer.commitSync(offsets);
                }
            }
            System.out.printf("seen=%d republished=%d still-invalid=%d%s%n", seen, replayed, skipped,
                    republish ? "" : " (dry run: pass --republish to send)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Ping tryParse(byte[] v) {
        try {
            return v == null ? null : Json.read(v, Ping.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        return h == null ? "" : new String(h.value(), StandardCharsets.UTF_8);
    }

    private DlqReplay() {
    }
}
