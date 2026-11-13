package dev.nexus.query;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param jwtSecret   HS256 secret shared with the gateway (from a secret store in production)
 * @param devTokens   enables POST /v1/dev/token; local and demo environments only
 * @param corsOrigins browser origins allowed to call this API
 */
@ConfigurationProperties(prefix = "nexus.query")
public record QueryProperties(String redisUrl, String jwtSecret, boolean devTokens, List<String> corsOrigins) {
}
