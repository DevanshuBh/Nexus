package dev.nexus.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceSignatureTest {

    private static final byte[] MASTER = "test-master-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"pings\":[]}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignatureVerifies() {
        byte[] key = DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0001", 1);
        String c = DeviceSignature.canonical("f01", "v0001", 1, 1000L, "n1", BODY);
        assertThat(DeviceSignature.verify(key, c, DeviceSignature.sign(key, c))).isTrue();
    }

    @Test
    void tamperedBodyFails() {
        byte[] key = DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0001", 1);
        String sig = DeviceSignature.sign(key, DeviceSignature.canonical("f01", "v0001", 1, 1000L, "n1", BODY));
        String tampered = DeviceSignature.canonical("f01", "v0001", 1, 1000L, "n1", "{\"pings\":[1]}".getBytes(StandardCharsets.UTF_8));
        assertThat(DeviceSignature.verify(key, tampered, sig)).isFalse();
    }

    @Test
    void anotherDevicesKeyCannotSign() {
        byte[] mine = DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0001", 1);
        byte[] other = DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0002", 1);
        String c = DeviceSignature.canonical("f01", "v0001", 1, 1000L, "n1", BODY);
        assertThat(DeviceSignature.verify(mine, c, DeviceSignature.sign(other, c))).isFalse();
    }

    @Test
    void rotatedKeyVersionDiffers() {
        assertThat(DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0001", 1))
                .isNotEqualTo(DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0001", 2));
    }

    @Test
    void garbageSignatureIsRejectedNotThrown() {
        byte[] key = DeviceSignature.deriveDeviceKey(MASTER, "f01", "v0001", 1);
        assertThat(DeviceSignature.verify(key, "x", "%%%not-base64")).isFalse();
        assertThat(DeviceSignature.verify(key, "x", null)).isFalse();
    }
}
