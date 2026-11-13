package dev.nexus.ingest;

import dev.nexus.common.DeviceSignature;
import dev.nexus.common.Json;
import dev.nexus.common.Ping;
import dev.nexus.common.PingValidator;
import dev.nexus.common.TelemetryBatch;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code POST /v1/telemetry}: a device uploads a signed batch of its own pings.
 *
 * <p>Response semantics (the device simulator implements the client side):
 * <ul>
 *   <li>202 — every ping has a result. {@code ACCEPTED}: on Kafka, never resend. {@code REJECTED}: invalid, never resend.
 *       {@code RETRY}: not acknowledged in time, resend later (resending is safe: storage dedups on eventId).</li>
 *   <li>401 — authentication failed (reason in body). 413 — too many pings. 429 — device over its rate limit.</li>
 *   <li>503 + Retry-After — Kafka is not accepting writes; nothing from this request was acknowledged.</li>
 * </ul>
 */
@RestController
public class TelemetryController {

    public record PingResult(String eventId, String status, String reason) {
    }

    public record TelemetryResponse(int accepted, int rejected, int retry, List<PingResult> results) {
    }

    private final DeviceAuthenticator auth;
    private final PingValidator validator;
    private final TelemetryPublisher publisher;
    private final DeviceRateLimiter rateLimiter;
    private final IngestProperties props;
    private final MeterRegistry metrics;

    public TelemetryController(DeviceAuthenticator auth, PingValidator validator, TelemetryPublisher publisher,
                               DeviceRateLimiter rateLimiter, IngestProperties props, MeterRegistry metrics) {
        this.auth = auth;
        this.validator = validator;
        this.publisher = publisher;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.metrics = metrics;
    }

    @PostMapping(path = "/v1/telemetry", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ingest(
            @RequestHeader(name = DeviceSignature.H_FLEET, required = false) String fleetId,
            @RequestHeader(name = DeviceSignature.H_DEVICE, required = false) String deviceId,
            @RequestHeader(name = DeviceSignature.H_KEY_VERSION, required = false) String keyVersion,
            @RequestHeader(name = DeviceSignature.H_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(name = DeviceSignature.H_NONCE, required = false) String nonce,
            @RequestHeader(name = DeviceSignature.H_SIGNATURE, required = false) String signature,
            @RequestBody byte[] body) {

        long now = System.currentTimeMillis();
        DeviceAuthenticator.Device device;
        try {
            device = auth.authenticate(fleetId, deviceId, keyVersion, timestamp, nonce, signature, body, now);
        } catch (DeviceAuthenticator.AuthException e) {
            metrics.counter("nexus_ingest_auth_failures", "reason", e.reason()).increment();
            return problem(HttpStatus.UNAUTHORIZED, e.reason(), null);
        }

        TelemetryBatch batch;
        try {
            batch = Json.read(body, TelemetryBatch.class);
        } catch (RuntimeException e) {
            return problem(HttpStatus.BAD_REQUEST, "malformed_json", null);
        }
        List<Ping> pings = batch == null || batch.pings() == null ? List.of() : batch.pings();
        if (pings.size() > props.maxPingsPerRequest()) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "too_many_pings", null);
        }
        String deviceKey = device.fleetId() + "/" + device.deviceId();
        int cost = Math.max(1, pings.size());
        if (!rateLimiter.tryAcquire(deviceKey, cost, System.nanoTime())) {
            metrics.counter("nexus_ingest_throttled", "fleet", device.fleetId()).increment(pings.size());
            return problem(HttpStatus.TOO_MANY_REQUESTS, "device_rate_limited", "1");
        }

        PingResult[] results = new PingResult[pings.size()];
        List<Ping> valid = new ArrayList<>(pings.size());
        List<Integer> validIndex = new ArrayList<>(pings.size());
        for (int i = 0; i < pings.size(); i++) {
            Ping p = pings.get(i);
            Optional<String> reason = validator.validate(p, device.deviceId(), now);
            if (reason.isPresent()) {
                results[i] = new PingResult(p == null ? null : p.eventId(), "REJECTED", reason.get());
                metrics.counter("nexus_ingest_rejected", "reason", reason.get()).increment();
            } else {
                // fleetId comes from the authenticated device, never from the body.
                valid.add(p.withFleetAndReceivedAt(device.fleetId(), now));
                validIndex.add(i);
            }
        }

        List<Boolean> acked = publisher.publishAndAwait(valid);
        int accepted = 0;
        int retry = 0;
        for (int j = 0; j < valid.size(); j++) {
            boolean ok = acked.get(j);
            results[validIndex.get(j)] = new PingResult(valid.get(j).eventId(), ok ? "ACCEPTED" : "RETRY", ok ? null : "not_acknowledged");
            if (ok) {
                accepted++;
            } else {
                retry++;
            }
        }
        int rejected = pings.size() - valid.size();
        metrics.counter("nexus_ingest_accepted").increment(accepted);
        metrics.counter("nexus_ingest_retry").increment(retry);

        if (!valid.isEmpty() && accepted == 0) {
            metrics.counter("nexus_ingest_unavailable").increment();
            rateLimiter.refund(deviceKey, cost); // our outage must not use up the device's budget
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "kafka_unavailable", "2");
        }
        return ResponseEntity.accepted().body(new TelemetryResponse(accepted, rejected, retry, List.of(results)));
    }

    private static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String reason, String retryAfter) {
        var b = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (retryAfter != null) {
            b.header("Retry-After", retryAfter);
        }
        return b.body(Map.of("status", status.value(), "title", status.getReasonPhrase(), "detail", reason));
    }
}
