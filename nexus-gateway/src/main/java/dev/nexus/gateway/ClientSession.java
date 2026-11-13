package dev.nexus.gateway;

import dev.nexus.common.Jwt;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One browser connection: who it is, which fleets it watches, and what is waiting to be sent to it. */
final class ClientSession {

    final Channel channel;
    final Jwt.Claims claims;
    final Set<String> fleets = ConcurrentHashMap.newKeySet();
    final CoalescingBuffer buffer;

    ClientSession(Channel channel, Jwt.Claims claims, int bufferCapacity) {
        this.channel = channel;
        this.claims = claims;
        this.buffer = new CoalescingBuffer(bufferCapacity);
    }

    /**
     * Runs on the channel's event loop every flush interval. Sends one frame with every pending position, but only
     * while the socket keeps up: if Netty's outbound buffer is above the high watermark the channel is not writable,
     * so we send nothing and let the coalescing buffer keep just the newest position per truck.
     *
     * @return number of positions written, or -1 if the client was not writable
     */
    int flush(GatewayMetrics m) {
        m.coalesced.increment(buffer.takeCoalesced());
        m.dropped.increment(buffer.takeDropped());
        if (!channel.isActive()) {
            return 0;
        }
        if (!channel.isWritable()) {
            m.deferred.increment();
            return -1;
        }
        List<CoalescingBuffer.Entry> items = buffer.drain();
        if (items.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder(items.size() * 140 + 32).append("{\"type\":\"positions\",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(items.get(i).json());
        }
        sb.append("]}");
        channel.writeAndFlush(new TextWebSocketFrame(sb.toString()));
        m.frames.increment();
        m.positionsOut.increment(items.size());
        return items.size();
    }
}
