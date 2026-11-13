package dev.nexus.gateway;

import com.sun.net.httpserver.HttpServer;
import dev.nexus.common.Json;
import dev.nexus.common.Position;
import dev.nexus.common.Topics;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Live-position gateway: Redis Pub/Sub in, WebSocket out.
 *
 * <p>Every gateway node subscribes to all fleets' channels; browsers can connect to any node. Backpressure is
 * per connection: Netty's write-buffer watermarks mark a slow socket unwritable, and while it is unwritable the
 * session sends nothing and only keeps the newest position per truck (see {@link CoalescingBuffer}).
 */
public final class GatewayMain {

    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        int port = Integer.parseInt(env.getOrDefault("PORT", "8090"));
        int metricsPort = Integer.parseInt(env.getOrDefault("METRICS_PORT", "9405"));
        long flushMs = Long.parseLong(env.getOrDefault("FLUSH_MS", "250"));
        int bufferCapacity = Integer.parseInt(env.getOrDefault("CLIENT_BUFFER_VEHICLES", "20000"));
        String redisUrl = env.getOrDefault("REDIS_URL", "redis://localhost:6379");
        byte[] jwtSecret = env.getOrDefault("JWT_SECRET", "dev-only-jwt-secret-change-me-0123456789").getBytes(StandardCharsets.UTF_8);
        Set<String> origins = Arrays.stream(env.getOrDefault("CORS_ORIGINS", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());

        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        GatewayMetrics metrics = new GatewayMetrics(registry);
        Hub hub = new Hub(metrics);

        RedisClient redis = RedisClient.create(redisUrl);
        var pubsub = redis.connectPubSub(); // Lettuce reconnects and re-subscribes automatically
        pubsub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String pattern, String channel, String message) {
                try {
                    Position p = Json.read(message, Position.class);
                    hub.onPosition(channel.substring("nexus:pos:".length()), p.vehicleId(), message);
                } catch (RuntimeException e) {
                    log.warn("bad position message on {}: {}", channel, e.toString());
                }
            }
        });
        pubsub.sync().psubscribe(Topics.POSITION_CHANNEL_PATTERN);

        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/ws").checkStartsWith(true).maxFramePayloadLength(16 * 1024).build();
        Channel server = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.TCP_NODELAY, true)
                // Above 256 KB queued, the channel turns unwritable; below 64 KB it is writable again.
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(64 * 1024, 256 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(60, 20, 0)) // close dead peers; ping every 20 s
                                .addLast(new HttpServerCodec())
                                .addLast(new HttpObjectAggregator(16 * 1024))
                                .addLast(new HttpAuthHandler(jwtSecret, origins, metrics))
                                .addLast(new WebSocketServerProtocolHandler(wsConfig))
                                .addLast(new WebSocketHandler(hub, metrics, flushMs, bufferCapacity));
                    }
                })
                .bind(port).sync().channel();

        HttpServer http = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        http.createContext("/metrics", ex -> {
            byte[] b = registry.scrape().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        http.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down gateway");
            server.close().syncUninterruptibly();
            workers.shutdownGracefully();
            boss.shutdownGracefully();
            pubsub.close();
            redis.shutdown();
            http.stop(0);
        }, "nexus-gateway-shutdown"));

        log.info("gateway listening on :{} (ws path /ws, flush {} ms), metrics on :{}", port, flushMs, metricsPort);
        server.closeFuture().sync();
    }

    private GatewayMain() {
    }
}
