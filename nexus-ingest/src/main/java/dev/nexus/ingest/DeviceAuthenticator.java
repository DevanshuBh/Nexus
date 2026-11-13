package dev.nexus.ingest;

import dev.nexus.common.DeviceSignature;
import dev.nexus.common.PingValidator;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

import java.nio.charset.StandardCharsets;

/**
 * Authenticates one device request: signature, key version, revocation, timestamp window, nonce.
 *
 * <p>Order matters: cheap stateless checks first, the signature next, and the nonce (a Redis write) last, so an
 * unauthenticated caller cannot fill the nonce store.
 */
public class DeviceAuthenticator {

    public record Device(String fleetId, String deviceId, int keyVersion) {
    }

    public static final class AuthException extends Exception {
        private final String reason;

        public AuthException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Redis set of revoked "fleet/device/version" (or "fleet/device/*" for all versions). */
    public static final String REVOKED_KEY = "nexus:revoked";

    private final byte[] masterKey;
    private final int currentKeyVersion;
    private final long windowMs;
    private final RedisCommands<String, String> redis;

    public DeviceAuthenticator(IngestProperties props, RedisCommands<String, String> redis) {
        this.masterKey = props.deviceMasterKey().getBytes(StandardCharsets.UTF_8);
        this.currentKeyVersion = props.currentKeyVersion();
        this.windowMs = props.requestWindow().toMillis();
        this.redis = redis;
    }

    public Device authenticate(String fleetId, String deviceId, String keyVersionHeader, String timestampHeader,
                               String nonce, String signature, byte[] body, long nowMillis) throws AuthException {
        if (!PingValidator.isSafeId(fleetId) || !PingValidator.isSafeId(deviceId)) {
            throw new AuthException("missing_or_invalid_device_headers");
        }
        if (nonce == null || nonce.length() < 16 || nonce.length() > 64 || !PingValidator.isSafeId(nonce)) {
            throw new AuthException("invalid_nonce");
        }
        int version;
        long ts;
        try {
            version = Integer.parseInt(keyVersionHeader);
            ts = Long.parseLong(timestampHeader);
        } catch (NumberFormatException | NullPointerException e) {
            throw new AuthException("invalid_version_or_timestamp");
        }
        // Rotation grace period: the current and the previous version are accepted.
        if (version != currentKeyVersion && version != currentKeyVersion - 1) {
            throw new AuthException("key_version_not_accepted");
        }
        if (Math.abs(nowMillis - ts) > windowMs) {
            throw new AuthException("timestamp_outside_window");
        }
        byte[] key = DeviceSignature.deriveDeviceKey(masterKey, fleetId, deviceId, version);
        String canonical = DeviceSignature.canonical(fleetId, deviceId, version, ts, nonce, body);
        if (!DeviceSignature.verify(key, canonical, signature)) {
            throw new AuthException("bad_signature");
        }
        String id = fleetId + "/" + deviceId;
        if (Boolean.TRUE.equals(redis.sismember(REVOKED_KEY, id + "/" + version))
                || Boolean.TRUE.equals(redis.sismember(REVOKED_KEY, id + "/*"))) {
            throw new AuthException("key_revoked");
        }
        // A nonce may be used once inside the window. Keep it for 2x the window so a replay at the edge is still caught.
        String ok = redis.set("nexus:nonce:" + id + ":" + nonce, "1", SetArgs.Builder.nx().px(2 * windowMs));
        if (!"OK".equals(ok)) {
            throw new AuthException("replayed_nonce");
        }
        return new Device(fleetId, deviceId, version);
    }
}
