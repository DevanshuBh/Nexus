package dev.nexus.common;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One shared, thread-safe JSON mapper with explicit settings, so behaviour does not depend on library defaults. */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            // Forward compatibility: a newer device may send fields this server does not know yet.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    public static byte[] write(Object value) {
        return MAPPER.writeValueAsBytes(value);
    }

    public static String writeString(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** @throws tools.jackson.core.JacksonException (unchecked) on malformed input */
    public static <T> T read(byte[] bytes, Class<T> type) {
        return MAPPER.readValue(bytes, type);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
