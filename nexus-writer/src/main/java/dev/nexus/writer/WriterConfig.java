package dev.nexus.writer;

import java.util.Map;

/** Writer configuration from environment variables (12-factor); defaults suit docker compose. */
public record WriterConfig(
        String kafkaBootstrap,
        String groupId,
        String topic,
        String jdbcUrl,
        String dbUser,
        String dbPassword,
        int dbPoolSize,
        String redisUrl,
        int batchSize,
        long lingerMs,
        int bisectMaxDepth,
        int metricsPort) {

    public static WriterConfig fromEnv(Map<String, String> env) {
        return new WriterConfig(
                env.getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092"),
                env.getOrDefault("WRITER_GROUP_ID", "nexus-writer"),
                env.getOrDefault("GPS_TOPIC", dev.nexus.common.Topics.GPS),
                env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/nexus"),
                env.getOrDefault("DB_USER", "nexus"),
                env.getOrDefault("DB_PASSWORD", "nexus"),
                Integer.parseInt(env.getOrDefault("DB_POOL_SIZE", "4")),
                env.getOrDefault("REDIS_URL", "redis://localhost:6379"),
                Integer.parseInt(env.getOrDefault("WRITER_BATCH_SIZE", "500")),
                Long.parseLong(env.getOrDefault("WRITER_LINGER_MS", "500")),
                Integer.parseInt(env.getOrDefault("WRITER_BISECT_MAX_DEPTH", "16")),
                Integer.parseInt(env.getOrDefault("METRICS_PORT", "9404")));
    }
}
