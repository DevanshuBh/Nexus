package dev.nexus.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTest {

    private static final byte[] SECRET = "jwt-test-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTrip() {
        String t = Jwt.issue(SECRET, "alice", Set.of("f01"), Set.of("dispatcher"), 60, 1_000);
        var claims = Jwt.verify(SECRET, t, 1_010).orElseThrow();
        assertThat(claims.subject()).isEqualTo("alice");
        assertThat(claims.canAccessFleet("f01")).isTrue();
        assertThat(claims.canAccessFleet("f02")).isFalse();
    }

    @Test
    void expiredTokenRejected() {
        String t = Jwt.issue(SECRET, "alice", Set.of("f01"), Set.of(), 60, 1_000);
        assertThat(Jwt.verify(SECRET, t, 1_060)).isEmpty();
    }

    @Test
    void wrongSecretRejected() {
        String t = Jwt.issue(SECRET, "alice", Set.of("f01"), Set.of(), 60, 1_000);
        assertThat(Jwt.verify("another-secret-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8), t, 1_010)).isEmpty();
    }

    @Test
    void algNoneRejected() {
        String t = Jwt.issue(SECRET, "alice", Set.of("f01"), Set.of(), 60, 1_000);
        String[] parts = t.split("\\.");
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(Jwt.verify(SECRET, none + "." + parts[1] + ".", 1_010)).isEmpty();
    }

    @Test
    void tamperedFleetClaimRejected() {
        String t = Jwt.issue(SECRET, "alice", Set.of("f01"), Set.of(), 60, 1_000);
        String[] parts = t.split("\\.");
        String evil = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"alice\",\"fleets\":[\"f02\"],\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
        assertThat(Jwt.verify(SECRET, parts[0] + "." + evil + "." + parts[2], 1_010)).isEmpty();
    }
}
