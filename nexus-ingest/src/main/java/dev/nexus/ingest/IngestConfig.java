package dev.nexus.ingest;

import dev.nexus.common.PingValidator;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class IngestConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(IngestProperties props) {
        RedisURI uri = RedisURI.create(props.redisUrl());
        uri.setTimeout(Duration.ofSeconds(1));
        return RedisClient.create(uri);
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect(); // thread-safe; shared by all request threads
    }

    @Bean
    RedisCommands<String, String> redisCommands(StatefulRedisConnection<String, String> c) {
        return c.sync();
    }

    @Bean
    DeviceAuthenticator deviceAuthenticator(IngestProperties props, RedisCommands<String, String> redis) {
        if (props.deviceMasterKey() == null || props.deviceMasterKey().length() < 32) {
            throw new IllegalStateException("nexus.ingest.device-master-key must be at least 32 characters");
        }
        return new DeviceAuthenticator(props, redis);
    }

    @Bean
    PingValidator pingValidator(IngestProperties props) {
        return new PingValidator(props.maxFutureSkew(), props.maxEventAge());
    }

    @Bean(destroyMethod = "close")
    TelemetryPublisher telemetryPublisher(IngestProperties props) {
        return new TelemetryPublisher(props);
    }

    @Bean
    DeviceRateLimiter deviceRateLimiter(IngestProperties props) {
        return new DeviceRateLimiter(props.devicePingsPerSecond());
    }
}
