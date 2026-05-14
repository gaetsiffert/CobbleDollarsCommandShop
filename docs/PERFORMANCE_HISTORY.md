# Performance History

This file tracks the benchmark checkpoints used during the 1.0.x optimization work.
It is intentionally technical. Release-facing notes belong in the root changelog files.

## Method

- Runtime scenarios are measured with the versioned harness documented in [TESTING.md](./TESTING.md).
- Figures below use the median of 3 identical runs.
- Each run measures:
  - `openShop`
  - `refreshPlayerSession`
  - `handleCustomSell`
- The heavy benchmark fixture is `bench-configs/heavy_shop_bank`.
- Raw reports are generated in `build/reports/perf/` and archived outside the repo by run set.

## Important limits

- `EphemeralTestServerProvider` is useful for registry/bootstrap tests, but not as a faithful world/player harness.
- Because of that, player/menu flows such as `openShop`, `refreshPlayerSession`, and `handleCustomSell` are tracked with GameTests, not with plain NeoForge JUnit runtime tests.
- Historical results are only comparable when they use the same benchmark fixture generation and the same harness version.
- Commit `d7b230d` is intentionally excluded here. It required a compatibility fixture and was not a strict apples-to-apples comparison against the later harness.

## Checkpoints

All timings are in milliseconds.

| Commit | Scope | Open avg | Open p95 | Refresh avg | Refresh p95 | Sell avg | Sell p95 | Notes |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `dd05fc3` | Harden transactions + runtime sync tuning | 1.003 | 1.543 | 1.778 | 2.686 | 2.092 | 2.769 | First stable checkpoint measured with the current harness generation. |
| `d91ace7` | Audit logger reuse + shop/bank runtime refresh optimizations | 0.998 | 1.249 | 1.896 | 2.264 | 1.850 | 2.677 | `sell` improved clearly; `refresh` tail improved, average stayed mixed. |
| `5325500` | Refresh runtime path + breakdown benchmarks | 0.662 | 0.826 | 1.662 | 1.948 | 1.841 | 2.513 | Big win on `open` and `refresh`, plus a usable breakdown for the hot path. |
| `Refactor shop runtime and session sync pipeline` | Session/runtime cleanup and architectural extraction | 0.721 | 0.780 | 1.457 | 1.568 | 1.814 | 2.358 | Maintains most of the previous gains while moving sync/runtime code into dedicated components. |

## Interpreting the current cleanup pass

Relative to `5325500`, the cleanup/restructure pass changed the hot paths as follows:

- `openShop`: `0.662 -> 0.721` avg (`+8.9%`), `0.826 -> 0.780` p95 (`-5.6%`)
- `refreshPlayerSession`: `1.662 -> 1.457` avg (`-12.3%`), `1.948 -> 1.568` p95 (`-19.5%`)
- `handleCustomSell`: `1.841 -> 1.814` avg (`-1.5%`), `2.513 -> 2.358` p95 (`-6.2%`)

Interpretation:

- The cleanup is not free, but it does not erase the earlier wins.
- `refreshPlayerSession` remains better than `5325500`.
- `handleCustomSell` remains marginally better than `5325500`.
- `openShop` average is slightly worse than `5325500`, but the tail stays better.

Relative to `d91ace7`, the same cleanup state is still ahead across the main scenarios:

- `openShop`: `-27.8%` avg, `-37.6%` p95
- `refreshPlayerSession`: `-23.2%` avg, `-30.7%` p95
- `handleCustomSell`: `-1.9%` avg, `-11.9%` p95

## Breakdown guidance

When `refreshPlayerSession` moves unexpectedly, inspect the breakdown report before changing code:

- `createRuntimeData`
- `refreshSessionShop`
- `syncClientShopUiState`

The previous optimization rounds showed that improvements in `refreshSessionShop` can be offset if snapshot or runtime preparation work is pushed back into `createRuntimeData`.

## Archive names used so far

- `dd05fc3`
- `d91ace7`
- `refresh-pass-5325500-after`
- `cleanup-pass-final2-after`

Keep future archives using the same pattern: one folder per checkpoint, with `run1`, `run2`, and `run3` subfolders.
