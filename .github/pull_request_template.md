## What changed

## How it was tested
- [ ] `./mvnw verify` (unit + Testcontainers)
- [ ] `cd web && npm run typecheck && npm test && npm run build`
- [ ] Chaos/benchmark run if the data path changed (numbers in docs/benchmarks.md)

## Risk
Failure modes considered (duplicate, out of order, slow, down):
