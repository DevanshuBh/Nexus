package dev.nexus.ingest;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-device token bucket at ingress.
 *
 * <p>Partitioning by vehicleId means one misbehaving device sending 100x lands on one Kafka partition and builds lag
 * there for every other truck hashed to it (a hot key). Limiting each device here caps that damage.
 * In-memory per API instance: with N instances a device can get up to N x the rate, which is acceptable for a
 * protective limit; per-fleet quotas that must be exact belong in Redis (see docs/design.md).
 */
public class DeviceRateLimiter {

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastNanos = now;
        }
    }

    private final double ratePerSecond;
    private final double capacity;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public DeviceRateLimiter(double ratePerSecond) {
        this.ratePerSecond = ratePerSecond;
        this.capacity = Math.max(1, ratePerSecond * 30); // 30 s of budget: a device back from a 90 s dead zone uploads its backlog in one request
    }

    /** @return true if {@code cost} pings may pass now */
    public boolean tryAcquire(String deviceKey, int cost, long nowNanos) {
        if (ratePerSecond <= 0) {
            return true;
        }
        Bucket b = buckets.computeIfAbsent(deviceKey, k -> new Bucket(capacity, nowNanos));
        synchronized (b) {
            double elapsed = (nowNanos - b.lastNanos) / 1e9;
            b.tokens = Math.min(capacity, b.tokens + elapsed * ratePerSecond);
            b.lastNanos = nowNanos;
            if (b.tokens >= cost) {
                b.tokens -= cost;
                return true;
            }
            return false;
        }
    }

    /**
     * Gives tokens back when the server, not the device, failed the request (503). Without this a Kafka outage makes
     * every device burn its budget on failed attempts and then get 429s while uploading its backlog after recovery.
     */
    public void refund(String deviceKey, int cost) {
        Bucket b = buckets.get(deviceKey);
        if (b == null) {
            return;
        }
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + cost);
        }
    }

    public double capacity() {
        return capacity;
    }
}
