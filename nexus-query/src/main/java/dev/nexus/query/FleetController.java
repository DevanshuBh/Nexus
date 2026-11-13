package dev.nexus.query;

import dev.nexus.common.Jwt;
import dev.nexus.common.PingValidator;
import dev.nexus.common.Topics;
import io.lettuce.core.GeoArgs;
import io.lettuce.core.GeoSearch;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads for the dispatcher console. Every endpoint is scoped to one fleet and the caller's token must grant it,
 * so a dispatcher cannot read another tenant's trucks.
 */
@RestController
public class FleetController {

    public record VehiclePosition(String vehicleId, long ts, double lat, double lon, double speedKph, double heading) {
    }

    public record Snapshot(String fleetId, long serverTime, int count, List<VehiclePosition> positions) {
    }

    public record TrackPoint(long ts, double lat, double lon, double speedKph) {
    }

    public record Track(String vehicleId, String resolution, List<TrackPoint> points) {
    }

    static final int MAX_SNAPSHOT = 20_000;
    /** Ranges longer than this are served from the 1-minute continuous aggregate. */
    static final Duration RAW_LIMIT = Duration.ofHours(6);

    private final StatefulRedisConnection<String, String> redis;
    private final JdbcTemplate jdbc;

    public FleetController(StatefulRedisConnection<String, String> redis, JdbcTemplate jdbc) {
        this.redis = redis;
        this.jdbc = jdbc;
    }

    /** Snapshot first, then subscribe: a (re)connecting map loads this, then applies live updates newer than it. */
    @GetMapping("/v1/fleets/{fleetId}/positions")
    public ResponseEntity<?> positions(@PathVariable String fleetId, @RequestParam(required = false) String bbox,
                                       @RequestAttribute(AuthInterceptor.CLAIMS) Jwt.Claims claims) throws Exception {
        if (!PingValidator.isSafeId(fleetId) || !claims.canAccessFleet(fleetId)) {
            return forbidden();
        }
        Bbox box = bbox == null ? null : Bbox.parse(bbox);
        var cmd = redis.sync();
        Collection<String> members = box == null
                ? cmd.zrange(Topics.geoKey(fleetId), 0, MAX_SNAPSHOT - 1)
                : cmd.geosearch(Topics.geoKey(fleetId), GeoSearch.fromCoordinates(box.centerLon(), box.centerLat()),
                GeoSearch.byBox(box.widthKm(), box.heightKm(), GeoArgs.Unit.km));

        List<String> ids = new ArrayList<>(members);
        if (ids.size() > MAX_SNAPSHOT) {
            ids = ids.subList(0, MAX_SNAPSHOT);
        }
        // All hashes in one pipelined round trip.
        var async = redis.async();
        List<RedisFuture<Map<String, String>>> futures = new ArrayList<>(ids.size());
        for (String id : ids) {
            futures.add(async.hgetall(Topics.vehicleKey(fleetId, id)));
        }
        LettuceFutures.awaitAll(5, TimeUnit.SECONDS, futures.toArray(new RedisFuture[0]));
        List<VehiclePosition> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            Map<String, String> h = futures.get(i).get();
            if (h == null || h.isEmpty()) {
                continue;
            }
            var p = new VehiclePosition(ids.get(i), Long.parseLong(h.get("ts")), Double.parseDouble(h.get("lat")),
                    Double.parseDouble(h.get("lon")), Double.parseDouble(h.get("spd")), Double.parseDouble(h.get("hdg")));
            if (box == null || box.contains(p.lon(), p.lat())) { // BYBOX is approximate at the edges
                out.add(p);
            }
        }
        return ResponseEntity.ok(new Snapshot(fleetId, System.currentTimeMillis(), out.size(), out));
    }

    @GetMapping("/v1/fleets/{fleetId}/vehicles/{vehicleId}/track")
    public ResponseEntity<?> track(@PathVariable String fleetId, @PathVariable String vehicleId,
                                   @RequestParam Instant from, @RequestParam Instant to,
                                   @RequestAttribute(AuthInterceptor.CLAIMS) Jwt.Claims claims) {
        if (!PingValidator.isSafeId(fleetId) || !PingValidator.isSafeId(vehicleId) || !claims.canAccessFleet(fleetId)) {
            return forbidden();
        }
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            return ResponseEntity.badRequest().body(Map.of("status", 400, "detail", "range must be positive and at most 31 days"));
        }
        boolean raw = Duration.between(from, to).compareTo(RAW_LIMIT) <= 0;
        // fleet_id is filtered too: a vehicle id alone must never leak another tenant's data.
        String sql = raw
                ? "SELECT event_ts, lat, lon, speed_kph FROM gps_events WHERE vehicle_id = ? AND fleet_id = ? "
                + "AND event_ts >= ? AND event_ts < ? ORDER BY event_ts LIMIT 20000"
                : "SELECT bucket, lat, lon, avg_speed_kph FROM gps_track_1m WHERE vehicle_id = ? AND fleet_id = ? "
                + "AND bucket >= ? AND bucket < ? ORDER BY bucket LIMIT 50000";
        List<TrackPoint> points = jdbc.query(sql, (rs, n) -> new TrackPoint(rs.getTimestamp(1).getTime(),
                        rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)),
                vehicleId, fleetId, Timestamp.from(from), Timestamp.from(to));
        return ResponseEntity.ok(new Track(vehicleId, raw ? "raw" : "1m", points));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "detail", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "detail", "fleet_not_permitted"));
    }
}
