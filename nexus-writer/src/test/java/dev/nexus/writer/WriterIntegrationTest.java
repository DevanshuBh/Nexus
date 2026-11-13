package dev.nexus.writer;

import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import dev.nexus.common.Topics;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests of the write path against real Kafka, TimescaleDB and Redis containers.
 * These are the evidence behind the "zero lost, zero duplicated" claims in the README.
 */
class WriterIntegrationTest {

    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.4.0"));
    static final PostgreSQLContainer db = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:2.32.0-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("nexus").withUsername("nexus").withPassword("nexus");
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:8.8.4-alpine"))
            .withExposedPorts(6379);

    static KafkaProducer<String, byte[]> producer;

    @BeforeAll
    static void start() {
        kafka.start();
        db.start();
        redis.start();
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false), new StringSerializer(), new ByteArraySerializer());
    }

    @AfterAll
    static void stop() {
        producer.close();
        redis.stop();
        db.stop();
        kafka.stop();
    }

    // ---------------------------------------------------------------- helpers

    static String redisUrl() {
        return "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    static WriterConfig config(String topic, String group) {
        return new WriterConfig(kafka.getBootstrapServers(), group, topic, db.getJdbcUrl(), "nexus", "nexus", 2,
                redisUrl(), 100, 200, 16, 0);
    }

    static String newTopic(int partitions) throws Exception {
        String t = "gps-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(t, partitions, (short) 1))).all().get();
            // Producing before leaders are elected makes the test producer retry with NOT_LEADER errors; wait first.
            await().atMost(Duration.ofSeconds(30)).until(() -> admin.describeTopics(List.of(t)).allTopicNames().get()
                    .get(t).partitions().stream().allMatch(p -> p.leader() != null && !p.leader().isEmpty()));
        }
        return t;
    }

    static Ping ping(String fleet, int vehicle, long ts) {
        return new Ping(UUID.randomUUID().toString(), fleet + "-v" + vehicle, fleet, ts, 12.97, 77.59, 40.0, 90.0,
                System.currentTimeMillis());
    }

    static void produce(String topic, List<Ping> pings) throws Exception {
        for (Ping p : pings) {
            producer.send(new ProducerRecord<>(topic, p.vehicleId(), Json.write(p)));
        }
        producer.flush();
    }

    static List<Ping> pings(String fleet, int n) {
        long now = System.currentTimeMillis();
        List<Ping> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(ping(fleet, i % 20, now - (n - i) * 1000L));
        }
        return out;
    }

    static long rows(String fleet) throws SQLException {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), "nexus", "nexus");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*), count(DISTINCT event_id) FROM gps_events WHERE fleet_id = '" + fleet + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("total rows == distinct events (no duplicates)").isEqualTo(rs.getLong(2));
            return rs.getLong(1);
        }
    }

    static long committed(String topic, String group) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> m = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
            return m.entrySet().stream().filter(e -> e.getKey().topic().equals(topic) && e.getValue() != null)
                    .mapToLong(e -> e.getValue().offset()).sum();
        }
    }

    static long endOffsets(String topic) throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var parts = admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic).partitions();
            Map<TopicPartition, OffsetSpec> req = new java.util.HashMap<>();
            parts.forEach(p -> req.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest()));
            return admin.listOffsets(req).all().get().values().stream().mapToLong(v -> v.offset()).sum();
        }
    }

    /** Dead letters whose key belongs to this fleet (keys are vehicle ids prefixed with the fleet). */
    static List<String> deadLetterKeys(String fleet) {
        try (var c = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-check-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of(Topics.GPS_DLQ));
            List<String> keys = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (r.key() != null && r.key().startsWith(fleet + "-")) {
                        keys.add(r.key());
                    }
                });
            }
            return keys;
        }
    }

    static Writer writer(String topic, String group, ConsumerLoop.CrashHook hook) {
        Writer w = new Writer(config(topic, group), new SimpleMeterRegistry(), hook);
        w.start();
        return w;
    }

    // ---------------------------------------------------------------- repository-level

    @Test
    void writingTheSameBatchTwiceAddsNothing() throws Exception {
        try (Writer w = new Writer(config("unused", "g-" + UUID.randomUUID()), new SimpleMeterRegistry(), ConsumerLoop.CrashHook.NONE)) {
            var repo = new CopyGpsEventRepository(w.dataSource());
            List<Ping> batch = pings("dup", 300);
            assertThat(repo.insertIgnoringDuplicates(batch)).isEqualTo(300);
            assertThat(repo.insertIgnoringDuplicates(batch)).isZero();
            assertThat(rows("dup")).isEqualTo(300);
        }
    }

    @Test
    void bisectionIsolatesBadRowsInRealPostgres() throws Exception {
        try (Writer w = new Writer(config("unused", "g-" + UUID.randomUUID()), new SimpleMeterRegistry(), ConsumerLoop.CrashHook.NONE)) {
            var repo = new CopyGpsEventRepository(w.dataSource());
            List<Ping> batch = new ArrayList<>(pings("bis", 500));
            // Three rows the database rejects: CHECK violation, NaN, NUL byte in text.
            batch.set(10, withLat(batch.get(10), 95.0));
            batch.set(200, withLat(batch.get(200), Double.NaN));
            Ping p = batch.get(420);
            batch.set(420, new Ping(p.eventId(), "bis-v\u0000x", p.fleetId(), p.eventTs(), p.lat(), p.lon(), p.speedKph(), p.heading(), p.receivedAt()));

            var result = new BatchWriter(repo, 16).write(batch, Function.identity());

            assertThat(result.inserted()).isEqualTo(497);
            assertThat(result.isolated()).hasSize(3);
            assertThat(result.isolated()).extracting(BatchWriter.Isolated::sqlState).containsExactlyInAnyOrder("23514", "23514", "22021");
            assertThat(rows("bis")).isEqualTo(497);
        }
    }

    static Ping withLat(Ping p, double lat) {
        return new Ping(p.eventId(), p.vehicleId(), p.fleetId(), p.eventTs(), lat, p.lon(), p.speedKph(), p.heading(), p.receivedAt());
    }

    // ---------------------------------------------------------------- end-to-end

    @Test
    void crashAfterDbCommitBeforeOffsetCommitLosesNothingAndDuplicatesNothing() throws Exception {
        String topic = newTopic(3);
        String group = "g-" + UUID.randomUUID();
        produce(topic, pings("crash", 250));

        AtomicBoolean crashed = new AtomicBoolean();
        Writer first = writer(topic, group, () -> {
            if (crashed.compareAndSet(false, true)) {
                throw new Error("simulated kill -9 between DB commit and offset commit");
            }
        });
        assertThat(first.loop().awaitStopped(60, TimeUnit.SECONDS)).isTrue();
        first.close();

        long afterCrash = rows("crash");
        assertThat(afterCrash).as("the first batch was committed to Postgres").isPositive();
        assertThat(committed(topic, group)).as("but no offsets were committed").isZero();

        try (Writer second = writer(topic, group, ConsumerLoop.CrashHook.NONE)) {
            await().atMost(Duration.ofSeconds(60)).until(() -> committed(topic, group) == 250);
        }
        assertThat(rows("crash")).isEqualTo(250);
        assertThat(deadLetterKeys("crash")).isEmpty();
    }

    @Test
    void badRecordsGoToDlqAndGoodOnesAreStored() throws Exception {
        String topic = newTopic(2);
        String group = "g-" + UUID.randomUUID();
        List<Ping> batch = new ArrayList<>(pings("dlq", 120));
        batch.set(5, withLat(batch.get(5), -123.0));
        produce(topic, batch);
        producer.send(new ProducerRecord<>(topic, "dlq-v99", "{not json".getBytes())).get();

        try (Writer w = writer(topic, group, ConsumerLoop.CrashHook.NONE)) {
            await().atMost(Duration.ofSeconds(60)).until(() -> committed(topic, group) == 121);
        }
        assertThat(rows("dlq")).isEqualTo(119);
        assertThat(deadLetterKeys("dlq")).containsExactlyInAnyOrder(batch.get(5).vehicleId(), "dlq-v99");
    }

    @Test
    void databaseOutagePausesAndResumesWithoutLossOrDlq() throws Exception {
        String topic = newTopic(3);
        String group = "g-" + UUID.randomUUID();
        produce(topic, pings("outage", 100));

        try (Writer w = writer(topic, group, ConsumerLoop.CrashHook.NONE)) {
            await().atMost(Duration.ofSeconds(60)).until(() -> rows("outage") == 100);

            setConnectionsAllowed(false); // Postgres refuses connections; existing ones are terminated
            produce(topic, pings("outage", 200));
            await().atMost(Duration.ofSeconds(60)).until(() -> !w.loop().isHealthy());
            Thread.sleep(3_000); // stay down across several retry cycles

            setConnectionsAllowed(true);
            await().atMost(Duration.ofSeconds(90)).until(() -> committed(topic, group) == endOffsets(topic));
        }
        assertThat(rows("outage")).isEqualTo(300);
        assertThat(deadLetterKeys("outage")).as("an outage must not park good rows in the DLQ").isEmpty();
    }

    static void setConnectionsAllowed(boolean allowed) throws SQLException {
        // Connect to the maintenance DB as superuser to toggle access to "nexus".
        String admin = db.getJdbcUrl().replace("/nexus", "/postgres");
        try (Connection c = DriverManager.getConnection(admin, "nexus", "nexus"); Statement st = c.createStatement()) {
            st.execute("ALTER DATABASE nexus WITH ALLOW_CONNECTIONS " + allowed);
            if (!allowed) {
                st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'nexus' AND pid <> pg_backend_pid()");
            }
        }
    }

    @Test
    void livePositionNeverMovesBackwards() {
        RedisClient client = RedisClient.create(redisUrl());
        try (var pub = new LivePositionPublisher(redisUrl()); var conn = client.connect()) {
            long now = System.currentTimeMillis();
            Ping fresh = ping("guard", 1, now);
            Ping hourOld = new Ping(UUID.randomUUID().toString(), fresh.vehicleId(), "guard", now - 3_600_000, 1.0, 2.0, 10.0, 0.0, now);

            assertThat(pub.publishNewest(List.of(fresh))).isEqualTo(1);
            assertThat(pub.publishNewest(List.of(hourOld))).as("replayed old ping is ignored").isZero();
            assertThat(pub.publishNewest(List.of(fresh))).as("same ping again is a no-op").isZero();

            var hash = conn.sync().hgetall(Topics.vehicleKey("guard", fresh.vehicleId()));
            assertThat(hash.get("ts")).isEqualTo(Long.toString(now));
            assertThat(Double.parseDouble(hash.get("lat"))).isEqualTo(12.97);
            assertThat(conn.sync().geopos(Topics.geoKey("guard"), fresh.vehicleId()).getFirst()).isNotNull();
        } finally {
            client.shutdown();
        }
    }
}
