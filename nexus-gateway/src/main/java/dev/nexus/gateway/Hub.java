package dev.nexus.gateway;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Routes each position to the sessions subscribed to its fleet. */
final class Hub {

    private final ConcurrentHashMap<String, Set<ClientSession>> byFleet = new ConcurrentHashMap<>();
    private final Set<ClientSession> all = ConcurrentHashMap.newKeySet();
    private final GatewayMetrics metrics;

    Hub(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    void register(ClientSession s) {
        all.add(s);
        metrics.connections.set(all.size());
    }

    void unregister(ClientSession s) {
        all.remove(s);
        for (String f : s.fleets) {
            unsubscribe(s, f);
        }
        metrics.connections.set(all.size());
    }

    void subscribe(ClientSession s, String fleetId) {
        s.fleets.add(fleetId);
        byFleet.computeIfAbsent(fleetId, k -> ConcurrentHashMap.newKeySet()).add(s);
    }

    void unsubscribe(ClientSession s, String fleetId) {
        s.fleets.remove(fleetId);
        byFleet.computeIfPresent(fleetId, (k, set) -> {
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }

    /** Called from the Redis Pub/Sub thread for every position. O(subscribers of that fleet), no I/O. */
    void onPosition(String fleetId, String vehicleId, String json) {
        metrics.messagesIn.increment();
        Set<ClientSession> subs = byFleet.get(fleetId);
        if (subs == null) {
            return;
        }
        for (ClientSession s : subs) {
            s.buffer.offer(vehicleId, json);
        }
    }

    Set<ClientSession> sessions() {
        return all;
    }
}
