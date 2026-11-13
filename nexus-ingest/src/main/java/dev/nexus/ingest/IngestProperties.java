package dev.nexus.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param deviceMasterKey   master secret all device keys are derived from (from a secret store in production)
 * @param currentKeyVersion newest key version; {@code currentKeyVersion - 1} is still accepted during rotation
 * @param requestWindow     how far a request timestamp may differ from server time (replay window)
 * @param ackTimeout        how long to wait for Kafka to acknowledge a batch before answering
 * @param devicePingsPerSecond per-device rate limit (hot-key protection), burst = 10x
 */
@ConfigurationProperties(prefix = "nexus.ingest")
public record IngestProperties(
        String kafkaBootstrap,
        String topic,
        String redisUrl,
        String deviceMasterKey,
        int currentKeyVersion,
        Duration requestWindow,
        Duration maxFutureSkew,
        Duration maxEventAge,
        Duration ackTimeout,
        int maxPingsPerRequest,
        double devicePingsPerSecond) {
}
