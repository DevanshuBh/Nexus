package dev.nexus.simulator;

import dev.nexus.common.DeviceSignature;
import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import dev.nexus.common.TelemetryBatch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;

/**
 * Generates realistic, signed device traffic for N trucks.
 *
 * <pre>
 *   java -jar nexus-simulator.jar --trucks 1000 --fleets 4 --interval-ms 3000
 *        [--target http://localhost:8080] [--burst 5] [--malformed-pct 1] [--skew-ms 2000]
 *        [--hot-devices 20 --hot-factor 100] [--hot-region-pct 10] [--offline-pct 5] [--duration-s 600]
 * </pre>
 * <ul>
 *   <li>--hot-devices: a few devices sending hot-factor times faster. Their pings share a key, so they land on few
 *       Kafka partitions: this is what creates hot partitions (and what the ingest rate limit defends against).</li>
 *   <li>--hot-region-pct: that share of trucks in one fleet sends 10x faster. Keys are spread, so Kafka stays balanced;
 *       the load lands on that fleet's live channel and gateway send queues instead.</li>
 *   <li>--offline-pct: that share of trucks repeatedly drops offline for 30-90 s, buffers, then uploads the backlog.</li>
 * </ul>
 * Only acknowledged pings are dropped from a device's buffer; RETRY, 429 and 503 answers are resent later.
 */
public final class Simulator {

    record Options(String target, int trucks, int fleets, long intervalMs, double burst, double malformedPct,
                   long skewMs, int hotDevices, double hotFactor, double hotRegionPct, double offlinePct,
                   long durationS, String masterKey, int keyVersion, double centerLat, double centerLon,
                   double radiusKm, int maxInFlight) {
    }

    private final Options o;
    private final HttpClient http;
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2);
    private final Semaphore inFlight;
    private final byte[] master;

    private final LongAdder requests = new LongAdder();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder unavailable = new LongAdder();
    private final LongAdder throttled = new LongAdder();
    private final LongAdder unauthorized = new LongAdder();
    private final LongAdder networkErrors = new LongAdder();

    Simulator(Options o) {
        this.o = o;
        this.master = o.masterKey().getBytes(StandardCharsets.UTF_8);
        this.inFlight = new Semaphore(o.maxInFlight());
        this.http = HttpClient.newBuilder().executor(senders).connectTimeout(Duration.ofSeconds(3)).build();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parse(args);
        Options o = new Options(
                a.getOrDefault("target", System.getenv().getOrDefault("INGEST_URL", "http://localhost:8080")),
                Integer.parseInt(a.getOrDefault("trucks", "100")),
                Integer.parseInt(a.getOrDefault("fleets", "2")),
                Long.parseLong(a.getOrDefault("interval-ms", "3000")),
                Double.parseDouble(a.getOrDefault("burst", "1")),
                Double.parseDouble(a.getOrDefault("malformed-pct", "0")),
                Long.parseLong(a.getOrDefault("skew-ms", "0")),
                Integer.parseInt(a.getOrDefault("hot-devices", "0")),
                Double.parseDouble(a.getOrDefault("hot-factor", "100")),
                Double.parseDouble(a.getOrDefault("hot-region-pct", "0")),
                Double.parseDouble(a.getOrDefault("offline-pct", "0")),
                Long.parseLong(a.getOrDefault("duration-s", "0")),
                a.getOrDefault("master-key", System.getenv().getOrDefault("DEVICE_MASTER_KEY", "dev-only-master-key-change-me-0123456789")),
                Integer.parseInt(a.getOrDefault("key-version", "1")),
                Double.parseDouble(a.getOrDefault("center-lat", "12.9716")),
                Double.parseDouble(a.getOrDefault("center-lon", "77.5946")),
                Double.parseDouble(a.getOrDefault("radius-km", "15")),
                Integer.parseInt(a.getOrDefault("max-in-flight", "256")));
        new Simulator(o).run();
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            String key = args[i].substring(2);
            m.put(key, i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true");
        }
        return m;
    }

    void run() throws InterruptedException {
        RandomGenerator rnd = ThreadLocalRandom.current();
        List<Truck> trucks = new ArrayList<>(o.trucks());
        int malformedEvery = o.malformedPct() > 0 ? 1 : 0;
        for (int i = 0; i < o.trucks(); i++) {
            String fleet = String.format("f%02d", i % o.fleets() + 1);
            String vehicle = String.format("%s-v%05d", fleet, i + 1);
            long skew = o.skewMs() == 0 ? 0 : rnd.nextLong(-o.skewMs(), o.skewMs() + 1);
            trucks.add(new Truck(fleet, vehicle, o.centerLat(), o.centerLon(), o.radiusKm(), skew, malformedEvery == 1, rnd));
        }
        int hotRegion = (int) Math.round(o.trucks() * o.hotRegionPct() / 100.0);
        int offlineCount = (int) Math.round(o.trucks() * o.offlinePct() / 100.0);
        long baseInterval = Math.max(10, Math.round(o.intervalMs() / o.burst()));

        for (int i = 0; i < trucks.size(); i++) {
            Truck t = trucks.get(i);
            long interval = baseInterval;
            if (i < o.hotDevices()) {
                interval = Math.max(10, Math.round(baseInterval / o.hotFactor()));
            } else if (i < o.hotDevices() + hotRegion && t.fleetId.equals("f01")) {
                interval = Math.max(10, baseInterval / 10);
            }
            ticker.scheduleAtFixedRate(() -> tick(t), rnd.nextLong(interval), interval, TimeUnit.MILLISECONDS);
        }
        for (int i = 0; i < offlineCount; i++) {
            Truck t = trucks.get(trucks.size() - 1 - i);
            ticker.scheduleAtFixedRate(() -> t.setOffline(!t.isOffline()), rnd.nextLong(30_000, 90_000),
                    rnd.nextLong(30_000, 90_000), TimeUnit.MILLISECONDS);
        }
        System.out.printf("simulating %d trucks in %d fleets every %d ms -> %s (hot devices %d, hot region %d, offline %d)%n",
                o.trucks(), o.fleets(), baseInterval, o.target(), o.hotDevices(), hotRegion, offlineCount);

        ticker.scheduleAtFixedRate(() -> {
            long backlog = trucks.stream().mapToLong(Truck::backlogSize).sum();
            System.out.printf("req=%d accepted=%d rejected=%d retry=%d 503=%d 429=%d 401=%d neterr=%d backlog=%d%n",
                    requests.sum(), accepted.sum(), rejected.sum(), retried.sum(), unavailable.sum(),
                    throttled.sum(), unauthorized.sum(), networkErrors.sum(), backlog);
        }, 5, 5, TimeUnit.SECONDS);

        if (o.durationS() > 0) {
            Thread.sleep(o.durationS() * 1000);
            ticker.shutdownNow();
            senders.shutdown();
            senders.awaitTermination(10, TimeUnit.SECONDS);
            System.out.printf("done: accepted=%d rejected=%d%n", accepted.sum(), rejected.sum());
        } else {
            Thread.currentThread().join(); // run until killed
        }
    }

    private void tick(Truck t) {
        try {
            t.buffer(t.tick(System.currentTimeMillis(), o.malformedPct() / 100.0));
            if (t.isOffline() || !inFlight.tryAcquire()) {
                return; // stays buffered: offline, or the simulator itself is at its concurrency limit
            }
            List<Ping> batch = t.takeBatch(500);
            if (batch.isEmpty()) {
                inFlight.release();
                return;
            }
            senders.execute(() -> {
                try {
                    send(t, batch);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RuntimeException e) {
            networkErrors.increment(); // never let an exception cancel the scheduled task
        }
    }

    private void send(Truck t, List<Ping> batch) {
        byte[] body = Json.write(new TelemetryBatch(batch));
        long ts = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString().replace("-", "");
        byte[] key = DeviceSignature.deriveDeviceKey(master, t.fleetId, t.vehicleId, o.keyVersion());
        String sig = DeviceSignature.sign(key, DeviceSignature.canonical(t.fleetId, t.vehicleId, o.keyVersion(), ts, nonce, body));
        HttpRequest req = HttpRequest.newBuilder(URI.create(o.target() + "/v1/telemetry"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(DeviceSignature.H_FLEET, t.fleetId)
                .header(DeviceSignature.H_DEVICE, t.vehicleId)
                .header(DeviceSignature.H_KEY_VERSION, Integer.toString(o.keyVersion()))
                .header(DeviceSignature.H_TIMESTAMP, Long.toString(ts))
                .header(DeviceSignature.H_NONCE, nonce)
                .header(DeviceSignature.H_SIGNATURE, sig)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        requests.increment();
        try {
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            switch (res.statusCode()) {
                case 202 -> handleResults(t, batch, res.body());
                case 503 -> {
                    unavailable.increment();
                    t.requeue(batch);
                }
                case 429 -> {
                    throttled.increment();
                    t.requeue(batch);
                }
                case 401 -> unauthorized.increment(); // a real device would re-provision; do not retry forever
                default -> {
                    rejected.add(batch.size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            t.requeue(batch);
        } catch (Exception e) {
            networkErrors.increment();
            t.requeue(batch); // the API may be restarting: keep the pings and try again
        }
    }

    @SuppressWarnings("unchecked")
    private void handleResults(Truck t, List<Ping> batch, byte[] body) {
        Map<String, Object> r = Json.read(body, Map.class);
        List<Map<String, Object>> results = (List<Map<String, Object>>) r.get("results");
        List<Ping> retry = new ArrayList<>();
        for (int i = 0; i < results.size() && i < batch.size(); i++) {
            switch (String.valueOf(results.get(i).get("status"))) {
                case "ACCEPTED" -> accepted.increment();
                case "RETRY" -> {
                    retried.increment();
                    retry.add(batch.get(i));
                }
                default -> rejected.increment();
            }
        }
        if (!retry.isEmpty()) {
            t.requeue(retry);
        }
    }
}
