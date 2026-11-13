package dev.nexus.writer;

import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import dev.nexus.writer.BatchWriter.RetryableBatchException;
import dev.nexus.writer.DeadLetterPublisher.DeadLetter;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The writer's poll loop: poll, buffer up to N records or T ms, write, then commit offsets.
 *
 * <p>Guarantees (each one has a test):
 * <ul>
 *   <li><b>No loss:</b> offsets are committed only after the rows are committed in Postgres (or acknowledged by the DLQ).
 *       A crash anywhere before {@code commitSync} causes redelivery.</li>
 *   <li><b>No duplicates:</b> redelivered rows hit {@code ON CONFLICT DO NOTHING}.</li>
 *   <li><b>Database outage:</b> partitions are paused but polling continues (so the group does not rebalance), and each
 *       partition is {@code seek()}ed back to the first uncommitted record. {@code pause()} alone would silently skip
 *       records that {@code poll()} had already returned.</li>
 * </ul>
 * Single-threaded by design: the KafkaConsumer, the buffer and the rebalance callbacks all run on this thread.
 */
public final class ConsumerLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLoop.class);

    /** Test seam for crash tests: called after the DB commit, before the offset commit. */
    @FunctionalInterface
    public interface CrashHook {
        void beforeOffsetCommit();

        CrashHook NONE = () -> { };
    }

    public record Settings(String topic, int batchSize, long lingerMs, long maxBackoffMs, int maxUnknownRetries) {
    }

    private record Item(ConsumerRecord<String, byte[]> record, Ping ping) {
    }

    private final KafkaConsumer<String, byte[]> consumer;
    private final GpsEventRepository repository;
    private final BatchWriter batchWriter;
    private final DeadLetterPublisher dlq;
    private final LivePositionPublisher live; // nullable: storage keeps working without Redis
    private final WriterMetrics metrics;
    private final Settings settings;
    private final CrashHook crashHook;

    private final List<ConsumerRecord<String, byte[]>> buffer = new ArrayList<>();
    private long bufferStartedAt;
    private boolean unhealthy;
    private boolean stuck;
    private long backoffMs;
    private long nextProbeAt;
    private int consecutiveFailures;

    private volatile boolean running = true;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public ConsumerLoop(KafkaConsumer<String, byte[]> consumer, GpsEventRepository repository, BatchWriter batchWriter,
                        DeadLetterPublisher dlq, LivePositionPublisher live, WriterMetrics metrics, Settings settings,
                        CrashHook crashHook) {
        this.consumer = consumer;
        this.repository = repository;
        this.batchWriter = batchWriter;
        this.dlq = dlq;
        this.live = live;
        this.metrics = metrics;
        this.settings = settings;
        this.crashHook = crashHook;
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(List.of(settings.topic()), new Rebalance());
            while (running) {
                if (unhealthy) {
                    whileUnhealthy();
                    continue;
                }
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(200));
                for (ConsumerRecord<String, byte[]> r : records) {
                    if (buffer.isEmpty()) {
                        bufferStartedAt = System.currentTimeMillis();
                    }
                    buffer.add(r);
                }
                if (shouldFlush()) {
                    flushOrPause();
                }
            }
            flushOnShutdown();
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
            flushOnShutdown(); // graceful stop: shutdown() called wakeup()
        } catch (Throwable t) {
            // Unknown state (or a simulated crash): do not try to flush. Uncommitted records are redelivered.
            buffer.clear();
            log.error("writer loop died", t);
            throw t;
        } finally {
            try {
                consumer.close(Duration.ofSeconds(10));
            } finally {
                stopped.countDown();
            }
        }
    }

    /** Called from the shutdown hook thread. Flushes the last batch, commits, and closes the consumer. */
    public void shutdown() throws InterruptedException {
        running = false;
        consumer.wakeup();
        if (!stopped.await(30, TimeUnit.SECONDS)) {
            log.warn("writer did not stop within 30s");
        }
    }

    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return stopped.await(timeout, unit);
    }

    public boolean isHealthy() {
        return !unhealthy && stopped.getCount() > 0;
    }

    private boolean shouldFlush() {
        return buffer.size() >= settings.batchSize()
                || (!buffer.isEmpty() && System.currentTimeMillis() - bufferStartedAt >= settings.lingerMs());
    }

    private void flushOrPause() {
        try {
            flush();
            consecutiveFailures = 0;
            backoffMs = 0;
        } catch (RetryableBatchException e) {
            log.warn("batch of {} not written ({}); pausing and rewinding", buffer.size(), e.getMessage());
            enterUnhealthy(e.errorClass());
        }
    }

    private void flush() throws RetryableBatchException {
        if (buffer.isEmpty()) {
            return;
        }
        List<Item> items = new ArrayList<>(buffer.size());
        List<DeadLetter> dead = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> r : buffer) {
            Ping p = parse(r);
            if (p == null) {
                dead.add(new DeadLetter(r, "MALFORMED", "", "value is not a structurally complete ping"));
            } else {
                items.add(new Item(r, p));
            }
        }

        long t0 = System.nanoTime();
        BatchWriter.Result<Item> result = batchWriter.write(items, Item::ping);
        metrics.writeLatency.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);

        Set<Item> isolated = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BatchWriter.Isolated<Item> iso : result.isolated()) {
            isolated.add(iso.item());
            dead.add(new DeadLetter(iso.item().record(), ErrorClass.BAD_DATA.name(), iso.sqlState(), iso.message()));
        }
        if (!dead.isEmpty()) {
            try {
                dlq.publishAll(dead);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RetryableBatchException(ErrorClass.UNKNOWN, e);
            } catch (Exception e) {
                // Committing offsets now would lose these rows: treat like an outage and retry the batch.
                throw new RetryableBatchException(ErrorClass.DB_UNHEALTHY, e);
            }
        }

        List<Ping> stored = new ArrayList<>(items.size());
        long now = System.currentTimeMillis();
        for (Item it : items) {
            if (!isolated.contains(it)) {
                stored.add(it.ping());
                metrics.eventAge.record(Math.max(0, now - it.ping().eventTs()), TimeUnit.MILLISECONDS);
            }
        }
        if (live != null && !stored.isEmpty()) {
            try {
                metrics.livePublished.increment(live.publishNewest(stored));
            } catch (RuntimeException e) {
                // Redis down degrades the live map; it must not stop storage.
                metrics.redisErrors.increment();
                log.warn("live position update failed: {}", e.toString());
            }
        }

        metrics.stored.increment(result.inserted());
        metrics.duplicates.increment(stored.size() - result.inserted());
        metrics.deadLettered.increment(dead.size());
        metrics.bisections.increment(result.bisections());
        metrics.flushes.increment();
        metrics.batchSize.set(buffer.size());

        crashHook.beforeOffsetCommit();
        try {
            consumer.commitSync(nextOffsets(buffer));
        } catch (WakeupException e) {
            throw e;
        } catch (KafkaException e) {
            // e.g. CommitFailedException after losing partitions: rows are stored, redelivery is deduplicated.
            log.warn("offset commit failed; records will be redelivered and deduplicated: {}", e.toString());
        }
        buffer.clear();
    }

    private static Ping parse(ConsumerRecord<String, byte[]> r) {
        if (r.value() == null) {
            return null;
        }
        try {
            Ping p = Json.read(r.value(), Ping.class);
            if (p == null || p.vehicleId() == null || p.fleetId() == null || p.eventTs() == null
                    || p.lat() == null || p.lon() == null || p.speedKph() == null || p.heading() == null) {
                return null;
            }
            return p;
        } catch (RuntimeException e) { // JacksonException is unchecked in Jackson 3
            return null;
        }
    }

    private static Map<TopicPartition, OffsetAndMetadata> nextOffsets(List<ConsumerRecord<String, byte[]>> records) {
        Map<TopicPartition, OffsetAndMetadata> out = new HashMap<>();
        for (ConsumerRecord<String, byte[]> r : records) {
            TopicPartition tp = new TopicPartition(r.topic(), r.partition());
            OffsetAndMetadata cur = out.get(tp);
            if (cur == null || r.offset() + 1 > cur.offset()) {
                out.put(tp, new OffsetAndMetadata(r.offset() + 1));
            }
        }
        return out;
    }

    private void enterUnhealthy(ErrorClass cls) {
        unhealthy = true;
        metrics.paused.set(1);
        metrics.retries.increment();
        consecutiveFailures++;
        if (cls == ErrorClass.UNKNOWN && consecutiveFailures >= settings.maxUnknownRetries()) {
            stuck = true;
            metrics.stuck.set(1);
            log.error("unknown database error {} times in a row; consumption stopped, operator action needed",
                    consecutiveFailures);
        }
        rewindToFirstUncommitted();
        consumer.pause(consumer.assignment());
        backoffMs = backoffMs == 0 ? 500 : Math.min(settings.maxBackoffMs(), backoffMs * 2);
        nextProbeAt = System.currentTimeMillis() + jitter(backoffMs);
    }

    /** Every polled record is in the buffer, so the smallest buffered offset per partition is the first uncommitted one. */
    private void rewindToFirstUncommitted() {
        Map<TopicPartition, Long> first = new HashMap<>();
        for (ConsumerRecord<String, byte[]> r : buffer) {
            first.merge(new TopicPartition(r.topic(), r.partition()), r.offset(), Math::min);
        }
        Set<TopicPartition> assigned = consumer.assignment();
        first.forEach((tp, offset) -> {
            if (assigned.contains(tp)) {
                consumer.seek(tp, offset);
            }
        });
        buffer.clear();
    }

    private void whileUnhealthy() {
        // Keep calling poll() so this member stays in the group (max.poll.interval.ms) while paused.
        ConsumerRecords<String, byte[]> leaked = consumer.poll(Duration.ofMillis(200));
        if (!leaked.isEmpty()) {
            // Only possible for a partition assigned without our pause; give the records back.
            for (TopicPartition tp : leaked.partitions()) {
                consumer.seek(tp, leaked.records(tp).getFirst().offset());
            }
            consumer.pause(leaked.partitions());
        }
        if (stuck || System.currentTimeMillis() < nextProbeAt) {
            return;
        }
        if (repository.isHealthy()) {
            log.info("database healthy again; resuming {} partitions", consumer.paused().size());
            consumer.resume(consumer.paused());
            unhealthy = false;
            metrics.paused.set(0);
        } else {
            backoffMs = Math.min(settings.maxBackoffMs(), backoffMs * 2);
            nextProbeAt = System.currentTimeMillis() + jitter(backoffMs);
        }
    }

    private void flushOnShutdown() {
        if (unhealthy || buffer.isEmpty()) {
            return;
        }
        try {
            flush();
        } catch (RetryableBatchException | RuntimeException e) {
            log.warn("final flush failed; {} records will be redelivered: {}", buffer.size(), e.toString());
            buffer.clear();
        }
    }

    /** Full jitter in [d/2, d]: spreads retries from many writers so they do not hit a recovering DB together. */
    private static long jitter(long d) {
        return d / 2 + ThreadLocalRandom.current().nextLong(d / 2 + 1);
    }

    private final class Rebalance implements ConsumerRebalanceListener {

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            if (!unhealthy && !buffer.isEmpty()) {
                try {
                    flush(); // commit what we have while we still own the partitions
                } catch (RetryableBatchException e) {
                    log.warn("flush on revoke failed: {}", e.getMessage());
                    enterUnhealthy(e.errorClass());
                }
            }
            dropBuffered(partitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            if (unhealthy) {
                consumer.pause(partitions);
            }
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            // Another member may already own them: never commit, just forget.
            dropBuffered(partitions);
        }

        private void dropBuffered(Collection<TopicPartition> partitions) {
            buffer.removeIf(r -> partitions.contains(new TopicPartition(r.topic(), r.partition())));
        }
    }
}
