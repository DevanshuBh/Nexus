package dev.nexus.writer;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class WriterMain {

    private static final Logger log = LoggerFactory.getLogger(WriterMain.class);

    public static void main(String[] args) throws Exception {
        WriterConfig cfg = WriterConfig.fromEnv(System.getenv());
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);

        Writer writer = new Writer(cfg, registry, ConsumerLoop.CrashHook.NONE);
        HttpServer http = startHttp(cfg.metricsPort(), registry, () -> writer.loop().isHealthy());

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean shuttingDown = new AtomicBoolean();
        // SIGTERM (docker stop, k8s): flush the last batch and commit before exiting.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shuttingDown.set(true);
            log.info("shutting down writer");
            try {
                writer.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                http.stop(0);
                done.countDown();
            }
        }, "nexus-writer-shutdown"));

        writer.start();
        log.info("writer started: topic={} batch={} linger={}ms", cfg.topic(), cfg.batchSize(), cfg.lingerMs());
        // If the loop dies (not via shutdown), exit non-zero so the orchestrator restarts us.
        // (Never call System.exit while the shutdown hook runs: that deadlocks.)
        writer.loop().awaitStopped(Long.MAX_VALUE, TimeUnit.DAYS);
        if (!shuttingDown.get()) {
            log.error("writer loop stopped unexpectedly; exiting");
            System.exit(1);
        }
        done.await();
    }

    static HttpServer startHttp(int port, PrometheusMeterRegistry registry, BooleanSupplier healthy) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", ex -> respond(ex, 200, "text/plain; version=0.0.4", registry.scrape()));
        // Liveness: the loop is running and not stuck on an outage.
        server.createContext("/health", ex -> {
            boolean ok = healthy.getAsBoolean();
            respond(ex, ok ? 200 : 503, "application/json", ok ? "{\"status\":\"UP\"}" : "{\"status\":\"DEGRADED\"}");
        });
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private WriterMain() {
    }
}
