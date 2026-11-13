package dev.nexus.writer;

import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import dev.nexus.common.Position;
import dev.nexus.common.Topics;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Updates each vehicle's last known position in Redis and publishes it to the live map, but only if the
 * ping is newer than what Redis already holds.
 *
 * <p>The compare, the write, the GEO index update and the publish run in one Lua script, so they are atomic:
 * two writers (or a replay of hour-old data racing live traffic) can never move a truck backwards, and an old
 * ping is never pushed to browsers. Both keys share the {fleet:ID} hash tag, so on a Redis Cluster the script
 * touches a single slot.
 */
public final class LivePositionPublisher implements AutoCloseable {

    static final String GUARD_SCRIPT = """
            local cur = redis.call('HGET', KEYS[1], 'ts')
            if cur and tonumber(cur) >= tonumber(ARGV[1]) then
              return 0
            end
            redis.call('HSET', KEYS[1], 'ts', ARGV[1], 'lat', ARGV[2], 'lon', ARGV[3], 'spd', ARGV[4], 'hdg', ARGV[5])
            local lat = tonumber(ARGV[2])
            -- Redis GEO cannot index the polar caps; the hash still holds the position there.
            if lat <= 85.05112878 and lat >= -85.05112878 then
              redis.call('GEOADD', KEYS[2], ARGV[3], ARGV[2], ARGV[6])
            end
            redis.call('PUBLISH', ARGV[7], ARGV[8])
            return 1
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> async;
    private volatile String sha;

    public LivePositionPublisher(String redisUrl) {
        RedisURI uri = RedisURI.create(redisUrl);
        uri.setTimeout(Duration.ofSeconds(2));
        this.client = RedisClient.create(uri);
        this.connection = client.connect();
        this.async = connection.async();
        this.sha = connection.sync().scriptLoad(GUARD_SCRIPT);
    }

    /** @return number of positions that were newer and therefore applied + published */
    public int publishNewest(Collection<Ping> pings) {
        // Only the newest ping per vehicle matters; an offline backlog of 500 pings becomes 1 script call.
        Map<String, Ping> newest = new HashMap<>();
        for (Ping p : pings) {
            newest.merge(p.vehicleId(), p, (a, b) -> b.eventTs() > a.eventTs() ? b : a);
        }
        try {
            return run(newest.values());
        } catch (RedisNoScriptException e) {
            // Redis restarted and lost its script cache.
            sha = connection.sync().scriptLoad(GUARD_SCRIPT);
            return run(newest.values());
        }
    }

    private int run(Collection<Ping> pings) {
        List<RedisFuture<Long>> futures = new ArrayList<>(pings.size());
        for (Ping p : pings) {
            String[] keys = {Topics.vehicleKey(p.fleetId(), p.vehicleId()), Topics.geoKey(p.fleetId())};
            String[] args = {
                    Long.toString(p.eventTs()),
                    Double.toString(p.lat()),
                    Double.toString(p.lon()),
                    Double.toString(p.speedKph()),
                    Double.toString(p.heading()),
                    p.vehicleId(),
                    Topics.positionChannel(p.fleetId()),
                    Json.writeString(Position.of(p))
            };
            futures.add(async.evalsha(sha, ScriptOutputType.INTEGER, keys, args));
        }
        if (!LettuceFutures.awaitAll(5, TimeUnit.SECONDS, futures.toArray(new RedisFuture[0]))) {
            throw new IllegalStateException("Redis did not answer within 5s");
        }
        int applied = 0;
        for (RedisFuture<Long> f : futures) {
            try {
                applied += f.get().intValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof RedisNoScriptException nse) {
                    throw nse;
                }
                throw new IllegalStateException(e.getCause());
            }
        }
        return applied;
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
