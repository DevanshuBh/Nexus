package dev.nexus.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Pending positions for one client: at most one (the newest) per vehicle, and at most {@code capacity} vehicles.
 *
 * <p>This is what bounds memory per connection. A slow client never accumulates a queue of every ping; it
 * accumulates at most one position per truck it watches, which is all a live map needs. Overwrites are counted
 * as "coalesced"; new vehicles beyond capacity are counted as "dropped".
 *
 * <p>Thread-safety: offered from the Redis listener thread, drained on the channel's event loop; methods are
 * synchronized on this small object, held only for map operations.
 */
public final class CoalescingBuffer {

    public record Entry(String vehicleId, String json) {
    }

    private final int capacity;
    private LinkedHashMap<String, String> pending = new LinkedHashMap<>();
    private long coalesced;
    private long dropped;

    public CoalescingBuffer(int capacity) {
        this.capacity = capacity;
    }

    /** @return false if dropped because the buffer is full of other vehicles */
    public synchronized boolean offer(String vehicleId, String json) {
        if (pending.containsKey(vehicleId)) {
            pending.put(vehicleId, json);
            coalesced++;
            return true;
        }
        if (pending.size() >= capacity) {
            dropped++;
            return false;
        }
        pending.put(vehicleId, json);
        return true;
    }

    public synchronized List<Entry> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>(pending.size());
        pending.forEach((k, v) -> out.add(new Entry(k, v)));
        pending = new LinkedHashMap<>();
        return out;
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized long takeCoalesced() {
        long c = coalesced;
        coalesced = 0;
        return c;
    }

    public synchronized long takeDropped() {
        long d = dropped;
        dropped = 0;
        return d;
    }
}
