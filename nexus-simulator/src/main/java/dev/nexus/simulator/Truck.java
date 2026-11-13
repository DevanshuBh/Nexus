package dev.nexus.simulator;

import dev.nexus.common.Ping;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One simulated vehicle driving between random waypoints around a city centre, stopping now and then.
 * Mutable state is guarded by the instance lock: ticks and HTTP responses arrive on different threads.
 */
final class Truck {

    final String fleetId;
    final String vehicleId;
    final long clockSkewMs;
    final boolean malformedProne;

    private final double centerLat;
    private final double centerLon;
    private final double radiusDeg;
    private final RandomGenerator rnd;

    private double lat;
    private double lon;
    private double targetLat;
    private double targetLon;
    private double speedKph;
    private double heading;
    private long stoppedUntil;
    private long lastMoveMs;

    /** Pings not yet acknowledged: offline backlog, retries, or waiting for a free request slot. */
    private final List<Ping> backlog = new ArrayList<>();
    private boolean offline;

    Truck(String fleetId, String vehicleId, double centerLat, double centerLon, double radiusKm, long clockSkewMs,
          boolean malformedProne, RandomGenerator rnd) {
        this.fleetId = fleetId;
        this.vehicleId = vehicleId;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusDeg = radiusKm / 111.0;
        this.clockSkewMs = clockSkewMs;
        this.malformedProne = malformedProne;
        this.rnd = rnd;
        double[] p = randomPoint();
        this.lat = p[0];
        this.lon = p[1];
        pickTarget();
        this.lastMoveMs = System.currentTimeMillis();
    }

    private double[] randomPoint() {
        double r = radiusDeg * Math.sqrt(rnd.nextDouble());
        double a = rnd.nextDouble() * 2 * Math.PI;
        return new double[]{centerLat + r * Math.sin(a), centerLon + r * Math.cos(a) / Math.cos(Math.toRadians(centerLat))};
    }

    private void pickTarget() {
        double[] t = randomPoint();
        targetLat = t[0];
        targetLon = t[1];
        speedKph = 20 + rnd.nextDouble() * 40;
    }

    /** Advances the truck to "now" and returns a fresh ping (possibly deliberately malformed). */
    synchronized Ping tick(long now, double malformedRate) {
        double dtH = (now - lastMoveMs) / 3_600_000.0;
        lastMoveMs = now;
        double speed = now < stoppedUntil ? 0 : speedKph;
        if (speed > 0) {
            double dLat = targetLat - lat;
            double dLon = (targetLon - lon) * Math.cos(Math.toRadians(lat));
            double distKm = Math.hypot(dLat, dLon) * 111.0;
            double stepKm = speed * dtH;
            heading = (Math.toDegrees(Math.atan2(dLon, dLat)) + 360) % 360;
            if (stepKm >= distKm) {
                lat = targetLat;
                lon = targetLon;
                pickTarget();
                if (rnd.nextDouble() < 0.3) {
                    stoppedUntil = now + 20_000 + rnd.nextLong(100_000); // delivery stop
                }
            } else {
                double f = stepKm / distKm;
                lat += (targetLat - lat) * f;
                lon += (targetLon - lon) * f;
            }
        }
        double reportedSpeed = now < stoppedUntil ? 0 : Math.max(0, speedKph + rnd.nextGaussian() * 2);
        Ping p = new Ping(UUID.randomUUID().toString(), vehicleId, null, now + clockSkewMs,
                round(lat), round(lon), Math.round(reportedSpeed * 10) / 10.0, Math.round(heading * 10) / 10.0, 0);
        if (malformedProne && rnd.nextDouble() < malformedRate) {
            p = switch (rnd.nextInt(3)) {
                case 0 -> new Ping(p.eventId(), vehicleId, null, p.eventTs(), 123.0, p.lon(), p.speedKph(), p.heading(), 0);
                case 1 -> new Ping(p.eventId(), vehicleId, null, null, p.lat(), p.lon(), p.speedKph(), p.heading(), 0);
                default -> new Ping(p.eventId(), vehicleId, null, p.eventTs(), p.lat(), p.lon(), -5.0, p.heading(), 0);
            };
        }
        return p;
    }

    private static double round(double d) {
        return Math.round(d * 1e6) / 1e6;
    }

    synchronized void buffer(Ping p) {
        backlog.add(p);
        if (backlog.size() > 20_000) {
            backlog.removeFirst(); // a real device has finite flash too
        }
    }

    synchronized void requeue(List<Ping> pings) {
        backlog.addAll(0, pings);
    }

    /** Takes up to {@code max} buffered pings, oldest first. */
    synchronized List<Ping> takeBatch(int max) {
        int n = Math.min(max, backlog.size());
        List<Ping> out = new ArrayList<>(backlog.subList(0, n));
        backlog.subList(0, n).clear();
        return out;
    }

    synchronized boolean isOffline() {
        return offline;
    }

    synchronized void setOffline(boolean offline) {
        this.offline = offline;
    }

    synchronized int backlogSize() {
        return backlog.size();
    }
}
