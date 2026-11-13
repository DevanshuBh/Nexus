package dev.nexus.query;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class QueryConfig implements WebMvcConfigurer {

    private final QueryProperties props;

    public QueryConfig(QueryProperties props) {
        if (props.jwtSecret() == null || props.jwtSecret().length() < 32) {
            throw new IllegalStateException("nexus.query.jwt-secret must be at least 32 characters");
        }
        this.props = props;
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient() {
        RedisURI uri = RedisURI.create(props.redisUrl());
        uri.setTimeout(Duration.ofSeconds(2));
        return RedisClient.create(uri);
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(RedisClient client) {
        return client.connect();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(props.jwtSecret())).addPathPatterns("/v1/fleets/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/**")
                .allowedOrigins(props.corsOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}
