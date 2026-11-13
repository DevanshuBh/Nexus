package dev.nexus.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 request signing for devices.
 *
 * <p>The signature covers fleet, device, key version, timestamp, nonce and a SHA-256 of the body,
 * so none of them can be changed or replayed into another context without the key.
 *
 * <p>Per-device keys are derived from one master key: {@code key = HMAC(master, fleet|device|version)}.
 * The server therefore stores one secret (in a secret store) rather than a readable table of device
 * secrets; rotating a device means bumping its version. See docs/adr/0004-device-auth.md for why this
 * was chosen over a KMS-encrypted key table or Ed25519.
 */
public final class DeviceSignature {

    public static final String H_DEVICE = "X-Device-Id";
    public static final String H_FLEET = "X-Fleet-Id";
    public static final String H_KEY_VERSION = "X-Key-Version";
    public static final String H_TIMESTAMP = "X-Timestamp";
    public static final String H_NONCE = "X-Nonce";
    public static final String H_SIGNATURE = "X-Signature";

    private static final String ALG = "HmacSHA256";

    private DeviceSignature() {
    }

    public static byte[] deriveDeviceKey(byte[] masterKey, String fleetId, String deviceId, int keyVersion) {
        String info = "nexus-device-key\n" + fleetId + "\n" + deviceId + "\n" + keyVersion;
        return hmac(masterKey, info.getBytes(StandardCharsets.UTF_8));
    }

    public static String canonical(String fleetId, String deviceId, int keyVersion, long timestamp, String nonce, byte[] body) {
        return "v1\n" + fleetId + "\n" + deviceId + "\n" + keyVersion + "\n" + timestamp + "\n" + nonce + "\n"
                + HexFormat.of().formatHex(sha256(body));
    }

    public static String sign(byte[] deviceKey, String canonical) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(deviceKey, canonical.getBytes(StandardCharsets.UTF_8)));
    }

    /** Constant-time comparison: the time taken does not reveal how many leading bytes matched. */
    public static boolean verify(byte[] deviceKey, String canonical, String signature) {
        if (signature == null) {
            return false;
        }
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected = hmac(deviceKey, canonical.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, provided);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALG); // Mac is not thread-safe; one per call
            mac.init(new SecretKeySpec(key, ALG));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
