# ADR 0004: HMAC device signatures with keys derived from one master secret

Status: accepted (Ed25519 is the planned successor)

## Context
Anyone could post fake GPS data. HMAC needs the verifier to hold each device's secret, which normally means a
readable table of secrets on the server.

## Decision
Device key = HMAC-SHA256(master, `fleet | device | version`). The server stores one master secret (in a secret store),
derives keys on demand, accepts the current and previous key version (rotation grace), and checks a Redis set of
revoked `fleet/device/version`. Requests carry a timestamp (±5 min window) and a nonce stored with `SET NX PX` for
twice the window. The signature covers fleet and device, so a device cannot post as another fleet.

## Consequences
- No per-device secret table to leak; compromise of the master key compromises every device, so it must live in a
  KMS/secret manager and be rotated by bumping the version.
- Ed25519 (devices hold private keys, server holds public keys only) removes that single point; it needs a device
  registry. Planned in the staff track.
