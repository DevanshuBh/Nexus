package dev.nexus.gateway;

import dev.nexus.common.Jwt;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Runs before the WebSocket upgrade: answers /health, and authenticates /ws.
 *
 * <p>Browsers cannot set an Authorization header on a WebSocket, so the token comes as {@code ?token=}. The Origin
 * header is checked against an allow-list, which blocks cross-site WebSocket hijacking from other pages.
 */
final class HttpAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final AttributeKey<Jwt.Claims> CLAIMS = AttributeKey.valueOf("nexus.claims");

    private final byte[] jwtSecret;
    private final Set<String> allowedOrigins;
    private final GatewayMetrics metrics;

    HttpAuthHandler(byte[] jwtSecret, Set<String> allowedOrigins, GatewayMetrics metrics) {
        this.jwtSecret = jwtSecret;
        this.allowedOrigins = allowedOrigins;
        this.metrics = metrics;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        QueryStringDecoder q = new QueryStringDecoder(req.uri());
        if (q.path().equals("/health")) {
            respond(ctx, HttpResponseStatus.OK, "{\"status\":\"UP\"}");
            return;
        }
        if (!q.path().equals("/ws")) {
            respond(ctx, HttpResponseStatus.NOT_FOUND, "{\"detail\":\"not_found\"}");
            return;
        }
        String origin = req.headers().get(HttpHeaderNames.ORIGIN);
        if (!allowedOrigins.isEmpty() && origin != null && !allowedOrigins.contains(origin)) {
            metrics.authFailures.increment();
            respond(ctx, HttpResponseStatus.FORBIDDEN, "{\"detail\":\"origin_not_allowed\"}");
            return;
        }
        List<String> tokens = q.parameters().get("token");
        var claims = Jwt.verify(jwtSecret, tokens == null ? null : tokens.getFirst(), System.currentTimeMillis() / 1000);
        if (claims.isEmpty()) {
            metrics.authFailures.increment();
            respond(ctx, HttpResponseStatus.UNAUTHORIZED, "{\"detail\":\"missing_or_invalid_token\"}");
            return;
        }
        ctx.channel().attr(CLAIMS).set(claims.get());
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(req.retain()); // on to the WebSocket handshake
    }

    private static void respond(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        var res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        res.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, res.content().readableBytes());
        ctx.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
    }
}
