package dev.nexus.gateway;

import dev.nexus.common.Json;
import dev.nexus.common.PingValidator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Client protocol (JSON text frames):
 * <pre>
 *   client → {"type":"subscribe","fleetId":"f01"}      server → {"type":"subscribed","fleetId":"f01"}
 *   client → {"type":"unsubscribe","fleetId":"f01"}
 *   server → {"type":"positions","items":[{vehicleId,fleetId,ts,lat,lon,speedKph,heading}, ...]}  every flush interval
 *   server → {"type":"error","detail":"..."}
 * </pre>
 */
final class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final Hub hub;
    private final GatewayMetrics metrics;
    private final long flushMs;
    private final int bufferCapacity;
    private ClientSession session;
    private ScheduledFuture<?> flusher;

    WebSocketHandler(Hub hub, GatewayMetrics metrics, long flushMs, int bufferCapacity) {
        this.hub = hub;
        this.metrics = metrics;
        this.flushMs = flushMs;
        this.bufferCapacity = bufferCapacity;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            session = new ClientSession(ctx.channel(), ctx.channel().attr(HttpAuthHandler.CLAIMS).get(), bufferCapacity);
            hub.register(session);
            // Batch per client: one frame per interval instead of one per ping (see docs/design.md, fan-out estimate).
            flusher = ctx.executor().scheduleAtFixedRate(() -> session.flush(metrics), flushMs, flushMs, TimeUnit.MILLISECONDS);
        } else if (evt instanceof IdleStateEvent idle) {
            if (idle.state() == IdleState.READER_IDLE) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1001, "idle")).addListener(f -> ctx.close());
            } else if (idle.state() == IdleState.WRITER_IDLE) {
                ctx.writeAndFlush(new PingWebSocketFrame()); // browser answers with pong, resetting reader idle
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        if (session == null) {
            return;
        }
        Map<?, ?> msg;
        try {
            msg = Json.read(frame.text(), Map.class);
        } catch (RuntimeException e) {
            send(ctx, "{\"type\":\"error\",\"detail\":\"malformed_message\"}");
            return;
        }
        Object type = msg.get("type");
        Object fleet = msg.get("fleetId");
        if (!(fleet instanceof String fleetId) || !PingValidator.isSafeId(fleetId)) {
            send(ctx, "{\"type\":\"error\",\"detail\":\"fleetId_required\"}");
            return;
        }
        if ("subscribe".equals(type)) {
            if (!session.claims.canAccessFleet(fleetId)) {
                send(ctx, "{\"type\":\"error\",\"detail\":\"fleet_not_permitted\"}");
                return;
            }
            hub.subscribe(session, fleetId);
            send(ctx, "{\"type\":\"subscribed\",\"fleetId\":\"" + fleetId + "\"}");
        } else if ("unsubscribe".equals(type)) {
            hub.unsubscribe(session, fleetId);
        } else {
            send(ctx, "{\"type\":\"error\",\"detail\":\"unknown_type\"}");
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (flusher != null) {
            flusher.cancel(false);
        }
        if (session != null) {
            hub.unregister(session);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    private static void send(ChannelHandlerContext ctx, String json) {
        ctx.writeAndFlush(new TextWebSocketFrame(json));
    }
}
