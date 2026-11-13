package dev.nexus.common;

import java.util.List;

/** Body of {@code POST /v1/telemetry}: one device uploading one or more pings (more after being offline). */
public record TelemetryBatch(List<Ping> pings) {
}
