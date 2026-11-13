package dev.nexus.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Minimal HS256 JWT issue/verify for dispatcher access.
 *
 * <p>Deliberately strict: only {@code alg=HS256} is accepted (no "none", no algorithm switching),
 * {@code exp} is required, and the signature is compared in constant time.
 * Swapping this for an OIDC provider (Keycloak) means verifying RS256 against a JWKS instead;
 * the {@link Claims} the services use stay the same.
 */
public final class Jwt {

    public record Claims(String subject, Set<String> fleets, Set<String> roles, long expiresAtEpochSec) {

        public boolean canAccessFleet(String fleetId) {
            return fleets.contains(fleetId) || roles.contains("admin");
        }
    }

    private static final String HEADER = b64(Json.writeString(Map.of("alg", "HS256", "typ", "JWT")).getBytes(StandardCharsets.UTF_8));

    private Jwt() {
    }

    public static String issue(byte[] secret, String subject, Set<String> fleets, Set<String> roles, long ttlSeconds, long nowEpochSec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", "nexus");
        payload.put("sub", subject);
        payload.put("fleets", List.copyOf(fleets));
        payload.put("roles", List.copyOf(roles));
        payload.put("iat", nowEpochSec);
        payload.put("exp", nowEpochSec + ttlSeconds);
        String body = b64(Json.write(payload));
        String signingInput = HEADER + "." + body;
        return signingInput + "." + b64(DeviceSignature.hmac(secret, signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    public static Optional<Claims> verify(byte[] secret, String token, long nowEpochSec) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            Map<String, Object> header = Json.read(Base64.getUrlDecoder().decode(parts[0]), Map.class);
            if (!"HS256".equals(header.get("alg"))) {
                return Optional.empty();
            }
            byte[] expected = DeviceSignature.hmac(secret, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            byte[] provided = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, provided)) {
                return Optional.empty();
            }
            Map<String, Object> p = Json.read(Base64.getUrlDecoder().decode(parts[1]), Map.class);
            if (!(p.get("exp") instanceof Number exp) || exp.longValue() <= nowEpochSec) {
                return Optional.empty();
            }
            Object sub = p.get("sub");
            return Optional.of(new Claims(
                    sub == null ? "" : sub.toString(),
                    toSet(p.get("fleets")),
                    toSet(p.get("roles")),
                    exp.longValue()));
        } catch (RuntimeException e) { // bad base64, bad JSON
            return Optional.empty();
        }
    }

    private static Set<String> toSet(Object o) {
        if (o instanceof List<?> list) {
            return Set.copyOf(list.stream().map(String::valueOf).toList());
        }
        return Set.of();
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
