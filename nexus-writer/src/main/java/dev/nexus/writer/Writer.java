package dev.nexus.writer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaClientMetrics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Wires one writer instance together. Used by {@link WriterMain} and by the integration tests. */
public final class Writer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Writer.class);

    private final HikariDataSource dataSource;
    private final DeadLetterPublisher dlq;
    private final LivePositionPublisher live;
    private final KafkaClientMetrics kafkaMetrics;
    private final ConsumerLoop loop;
    private final Thread thread;

    public Writer(WriterConfig cfg, MeterRegistry registry, ConsumerLoop.CrashHook crashHook) {
        this.dataSource = dataSource(cfg);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        this.dlq = new DeadLetterPublisher(cfg.kafkaBootstrap());
        this.live = connectRedis(cfg.redisUrl());
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.kafkaBootstrap(),
                ConsumerConfig.GROUP_ID_CONFIG, cfg.groupId(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, cfg.batchSize(),
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000,
                ConsumerConfig.CLIENT_ID_CONFIG, "nexus-writer"),
                new StringDeserializer(), new ByteArrayDeserializer());
        this.kafkaMetrics = new KafkaClientMetrics(consumer); // per-partition records-lag
        kafkaMetrics.bindTo(registry);

        var repo = new CopyGpsEventRepository(dataSource);
        this.loop = new ConsumerLoop(consumer, repo, new BatchWriter(repo, cfg.bisectMaxDepth()), dlq, live,
                new WriterMetrics(registry),
                new ConsumerLoop.Settings(cfg.topic(), cfg.batchSize(), cfg.lingerMs(), 30_000, 5),
                crashHook);
        this.thread = new Thread(loop, "nexus-writer-loop");
    }

    public void start() {
        thread.start();
    }

    public ConsumerLoop loop() {
        return loop;
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    /** Graceful: flush the last batch, commit, close. */
    @Override
    public void close() throws InterruptedException {
        if (thread.isAlive()) {
            loop.shutdown();
        }
        thread.join(TimeUnit.SECONDS.toMillis(30));
        kafkaMetrics.close();
        dlq.close();
        if (live != null) {
            live.close();
        }
        dataSource.close();
    }

    private static HikariDataSource dataSource(WriterConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl());
        hc.setUsername(cfg.dbUser());
        hc.setPassword(cfg.dbPassword());
        hc.setMaximumPoolSize(cfg.dbPoolSize());
        hc.setPoolName("nexus-writer");
        hc.setConnectionTimeout(5_000);
        // Created once per physical connection, reused by every batch on it.
        // IF NOT EXISTS: Flyway borrows the first connection before gps_events exists; the staging table does not depend on it.
        hc.setConnectionInitSql(CopyGpsEventRepository.STAGING_DDL);
        // Fail fast at start-up only if the DB is really unreachable; compose healthchecks order start-up.
        hc.setInitializationFailTimeout(60_000);
        return new HikariDataSource(hc);
    }

    private static LivePositionPublisher connectRedis(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return new LivePositionPublisher(url);
        } catch (RuntimeException e) {
            // Storage must work without Redis. Live map is degraded until the writer restarts.
            log.warn("Redis unavailable at start-up ({}); running without live positions", e.toString());
            return null;
        }
    }
}
