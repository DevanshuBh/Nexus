# ADR 0005: HS256 JWTs with fleet claims; dev token endpoint instead of an OIDC provider (for now)

Status: accepted for demo environments

## Context
Every read and every WebSocket subscription must be filtered by fleet. A full OIDC provider (Keycloak) needs ~1 GB of
RAM, which does not fit alongside the data path on the free VM.

## Decision
Query API and gateway verify HS256 JWTs (strict: `alg` must be HS256, `exp` required, constant-time compare) carrying
`fleets` and `roles`. `POST /v1/dev/token` issues tokens only when `DEV_TOKENS=true`.

## Consequences
- Tenant checks are real and tested; the token issuer is the only stand-in.
- Moving to Keycloak/Cognito means verifying RS256 against a JWKS; the claims the services use stay the same.
- Browsers cannot set headers on WebSockets, so the token travels as `?token=`; the gateway also checks `Origin`.
