# Threat model (STRIDE, abbreviated)

| Threat | Where | Mitigation | Evidence |
|---|---|---|---|
| Spoofed device | `POST /v1/telemetry` | HMAC-SHA256 with per-device keys derived from a master secret; the signature covers fleet and device | `DeviceAuthenticatorTest` |
| Replay of a captured request | ingest | ±5 min timestamp window and a single-use nonce (`SET NX PX`) | `rejectsReplayedNonce`, `rejectsOldStolenMessage` |
| Tampered body | ingest | SHA-256 of the body is inside the signed string | `rejectsBadSignatureAndTamperedBody` |
| Device reporting another truck | ingest | each ping's vehicleId must equal the authenticated device; fleet comes from auth, never the body | `PingValidatorTest.deviceCanOnlyReportItself` |
| Stolen device key | ingest | key versions with a rotation grace period; revocation set in Redis | `revokedKeyRejected`, `keyRotationGracePeriod` |
| Cross-tenant read | query API, gateway | JWT fleet claims checked on every endpoint and subscription; SQL also filters by `fleet_id` | 403 / `fleet_not_permitted` (verified against the running stack) |
| Forged or downgraded JWT | query, gateway | HS256 only, `alg=none` rejected, `exp` required, constant-time compare | `JwtTest` |
| Cross-site WebSocket hijacking | gateway | Origin allow-list plus a required token | `HttpAuthHandler` |
| Injection through ids | Redis keys, CSV, logs | ids limited to `[A-Za-z0-9_-]{1,64}`; CSV fields quoted; SQL parameterised | `PingValidatorTest.rejectsUnsafeIds` |
| DoS: huge or hot device | ingest | 1,000 pings per request, per-device token bucket (429), fast-fail 503 instead of unbounded buffering | `DeviceRateLimiterTest` |
| DoS: slow browser | gateway | bounded per-client buffer and a writability check | `CoalescingBufferTest` |
| XSS in the console | web | React escaping, no raw HTML; CSP, HSTS and nosniff headers (`web/vercel.json`) | |
| Secrets in Git | all | dev defaults only; real values come from the environment or a secret store | |

Known gaps: the dev token endpoint stands in for OIDC ([ADR 0005](adr/0005-dispatcher-auth.md)); one master device
secret ([ADR 0004](adr/0004-device-auth.md)); no TLS inside the Compose network (terminate TLS at the edge proxy when
deployed).
