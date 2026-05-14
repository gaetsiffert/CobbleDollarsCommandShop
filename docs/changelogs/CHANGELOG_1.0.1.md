Maintenance and performance patch

## Highlights

- Lower runtime overhead on large custom shop and bank configurations.
- Faster shop open and session refresh paths on the heavy benchmark pack used during development.
- Lower audit log I/O overhead during transactions.
- Internal test and benchmark coverage expanded to make future regressions easier to catch.

## Measured performance notes

- `openShop`: about `28%` lower average latency and `38%` lower p95 latency
- `refreshPlayerSession`: about `23%` lower average latency and `31%` lower p95 latency
- `handleCustomSell`: about `12%` lower p95 latency

These figures come from the heavy benchmark fixture tracked in `docs/PERFORMANCE_HISTORY.md` on github.
