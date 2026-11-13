package dev.nexus.writer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Every number the dashboard and alerts need from the writer. */
public final class WriterMetrics {

    final Counter stored;
    final Counter duplicates;
    final Counter deadLettered;
    final Counter bisections;
    final Counter flushes;
    final Counter retries;
    final Counter redisErrors;
    final Counter livePublished;
    final Timer writeLatency;
    final Timer eventAge;
    final AtomicInteger batchSize = new AtomicInteger();
    final AtomicInteger paused = new AtomicInteger();
    final AtomicInteger stuck = new AtomicInteger();

    public WriterMetrics(MeterRegistry r) {
        stored = Counter.builder("nexus_writer_events_stored").description("Rows inserted").register(r);
        duplicates = Counter.builder("nexus_writer_events_duplicate").description("Rows skipped by ON CONFLICT").register(r);
        deadLettered = Counter.builder("nexus_writer_events_dlq").description("Records parked in the DLQ").register(r);
        bisections = Counter.builder("nexus_writer_bisections").register(r);
        flushes = Counter.builder("nexus_writer_flushes").register(r);
        retries = Counter.builder("nexus_writer_batch_retries").description("Whole-batch retries after a non-data error").register(r);
        redisErrors = Counter.builder("nexus_writer_redis_errors").register(r);
        livePublished = Counter.builder("nexus_writer_live_published").description("Positions newer than Redis, published").register(r);
        writeLatency = Timer.builder("nexus_writer_write_latency").description("COPY + INSERT per batch, incl. bisection")
                .publishPercentileHistogram().register(r);
        // Event age = stored time minus device time. Lag can be zero while this is high (device buffering, clock skew).
        eventAge = Timer.builder("nexus_event_age").description("Device event time to stored")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofHours(2))
                .register(r);
        r.gauge("nexus_writer_batch_size", batchSize);
        r.gauge("nexus_writer_paused", paused);
        r.gauge("nexus_writer_stuck", stuck);
    }
}
