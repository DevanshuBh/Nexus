package dev.nexus.ingest;

import dev.nexus.common.DeviceSignature;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceAuthenticatorTest {

    static final String MASTER = "unit-test-master-key-0123456789abcdef";
    static final byte[] BODY = "{\"pings\":[]}".getBytes(StandardCharsets.UTF_8);
    static final long NOW = 1_800_000_000_000L;

    final Set<String> nonces = new HashSet<>();
    final Set<String> revoked = new HashSet<>();

    /** In-memory stand-in for the two Redis commands the authenticator uses. */
    @SuppressWarnings("unchecked")
    RedisCommands<String, String> fakeRedis() {
        return (RedisCommands<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{RedisCommands.class}, (proxy, m, args) -> switch (m.getName()) {
                    case "set" -> {
                        assertThat(args[2]).isInstanceOf(SetArgs.class);
                        yield nonces.add((String) args[0]) ? "OK" : null;
                    }
                    case "sismember" -> revoked.contains((String) args[1]);
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    DeviceAuthenticator auth(int currentVersion) {
        var props = new IngestProperties("x", "t", "redis://x", MASTER, currentVersion, Duration.ofMinutes(5),
                Duration.ofSeconds(30), Duration.ofDays(7), Duration.ofSeconds(5), 1000, 2);
        return new DeviceAuthenticator(props, fakeRedis());
    }

    static String sign(int version, long ts, String nonce, byte[] body) {
        byte[] key = DeviceSignature.deriveDeviceKey(MASTER.getBytes(StandardCharsets.UTF_8), "f01", "v0001", version);
        return DeviceSignature.sign(key, DeviceSignature.canonical("f01", "v0001", version, ts, nonce, body));
    }

    static final String NONCE = "nonce0123456789abcdef";

    @Test
    void acceptsValidRequest() throws Exception {
        var d = auth(1).authenticate("f01", "v0001", "1", Long.toString(NOW), NONCE, sign(1, NOW, NONCE, BODY), BODY, NOW);
        assertThat(d.fleetId()).isEqualTo("f01");
    }

    @Test
    void rejectsReplayedNonce() throws Exception {
        var a = auth(1);
        String sig = sign(1, NOW, NONCE, BODY);
        a.authenticate("f01", "v0001", "1", Long.toString(NOW), NONCE, sig, BODY, NOW);
        assertThatThrownBy(() -> a.authenticate("f01", "v0001", "1", Long.toString(NOW), NONCE, sig, BODY, NOW + 1000))
                .hasMessage("replayed_nonce");
    }

    @Test
    void rejectsOldStolenMessage() {
        long old = NOW - Duration.ofMinutes(10).toMillis();
        assertThatThrownBy(() -> auth(1).authenticate("f01", "v0001", "1", Long.toString(old), NONCE, sign(1, old, NONCE, BODY), BODY, NOW))
                .hasMessage("timestamp_outside_window");
        assertThat(nonces).as("a rejected request must not consume a nonce").isEmpty();
    }

    @Test
    void rejectsBadSignatureAndTamperedBody() {
        assertThatThrownBy(() -> auth(1).authenticate("f01", "v0001", "1", Long.toString(NOW), NONCE, sign(1, NOW, NONCE, BODY),
                "{\"pings\":[{}]}".getBytes(StandardCharsets.UTF_8), NOW)).hasMessage("bad_signature");
    }

    @Test
    void deviceCannotClaimAnotherFleet() {
        // signed for fleet f01, presented as f02
        assertThatThrownBy(() -> auth(1).authenticate("f02", "v0001", "1", Long.toString(NOW), NONCE, sign(1, NOW, NONCE, BODY), BODY, NOW))
                .hasMessage("bad_signature");
    }

    @Test
    void keyRotationGracePeriod() throws Exception {
        var a = auth(3);
        a.authenticate("f01", "v0001", "2", Long.toString(NOW), NONCE, sign(2, NOW, NONCE, BODY), BODY, NOW); // previous: ok
        assertThatThrownBy(() -> a.authenticate("f01", "v0001", "1", Long.toString(NOW), NONCE + "x", sign(1, NOW, NONCE + "x", BODY), BODY, NOW))
                .hasMessage("key_version_not_accepted");
    }

    @Test
    void revokedKeyRejected() {
        revoked.add("f01/v0001/1");
        assertThatThrownBy(() -> auth(1).authenticate("f01", "v0001", "1", Long.toString(NOW), NONCE, sign(1, NOW, NONCE, BODY), BODY, NOW))
                .hasMessage("key_revoked");
    }
}
