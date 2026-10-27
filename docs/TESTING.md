# Testing Strategy

This repository uses three complementary test layers plus a separate performance harness. Keep all of them.

## Test layers

### 1. Logic-focused JUnit tests

Location: `src/test/java`

Use these when the code under test is mostly deterministic business logic:

- parsing and validation
- stock/restock calculations
- item match resolution
- small helper classes
- benchmark config invariants
- focused persistence round-trips

These tests should stay narrow, fast, and precise. They should not require a world, a player session, or a running server fixture unless that is the thing being tested.

Examples in this repo:

- `StackCountMathTest`
- `RestockRuleTest`
- `TimeWindowMathTest`
- `ItemMatchExpressionTest`
- `ShopFilesParsingTest`
- `BankFilesParsingTest`
- `ShopVisibilityDataPersistenceTest`
- `PlayerShopStockDataPersistenceTest`

### 2. NeoForge runtime JUnit tests

Location: `src/test/java`  
Marker: `@ExtendWith(EphemeralTestServerProvider.class)`

Use these when the code needs a real NeoForge/Minecraft server bootstrap, but does not need a world-driven scenario or a real player/menu flow.

This is the right layer for:

- `ShopRegistry`
- registry-backed parsing
- bootstrap-sensitive initialization
- runtime services that only need the server process

Examples in this repo:

- `ShopRegistryServerTest`
- `CommandShopPerformanceTest`

### 3. GameTests

Location: `src/gametest/java`

Use these for end-to-end server flows:

- commands
- queued opens
- active session lifecycle
- stock changes driven through command/session code
- menu transitions
- player-bound runtime offer/bank resolution

GameTests run on a dedicated `gameTestServer` launch and exercise the real server
loop. The separate opt-in multiplayer smoke test below also covers a real client
connection to an ordinary dedicated server.

Examples in this repo:

- `CommandShopRegistryGameTests`
- `CommandShopCommandGameTests`
- `CommandShopStockGameTests`
- `CommandShopSessionGameTests`
- `CommandShopResolutionGameTests`
- `CommandShopPerformanceGameTests`

## How the tasks map

Run all standard JUnit tests:

```powershell
./gradlew.bat test
```

Run all standard GameTests:

```powershell
./gradlew.bat runGameTestServer
```

Run the full standard test pass:

```powershell
./gradlew.bat test runGameTestServer
```

### NPC compatibility checks

The NPC command-source GameTests run when Easy NPC or CustomNPCs is installed in
the development runtime; they do not add a required NPC dependency to the addon.
CustomNPCs 1.21.1.20251230 expects an ordinary dedicated server and cannot initialize
its world directory in the special GameTestServer launcher.

For that combination, use a fresh isolated development server directory, accept
the Minecraft EULA for that test server, and run:

```powershell
./gradlew.bat runServer '-Pcommandshops.serverTestDirectory=build/release-tests/compatibility-server' '-Pcommandshops.dedicatedCompatibilityTests=true'
```

The Gradle property sets both `-Dneoforge.enableGameTest=true` and
`-Dcommandshops.dedicatedCompatibilityTests=true` on the game JVM.
`DedicatedCompatibilityTestRunner` runs the registered tests on the ordinary
dedicated server and stops it afterwards. Leave this fixture's configuration
directory fresh so the addon generates its default shops, including
`general_store`. Reusing a customized directory that lacks the default shops is
not a valid fixture for the full registered suite.

Check `build/release-tests/compatibility-server/logs/latest.log` for
`COMPATIBILITY_RESULT: <done> / <total> complete, <failures> failures.` Require
`done == total`, `total > 0` and `failures == 0`. The JVM can exit successfully
after a failed or incomplete test run, so its exit code alone is insufficient.
These helpers are in the GameTest source set and are excluded from the
distributed addon jar.

### Isolated client/server test directories

The normal `Client` and `Server` run configurations use `run` by default.
`-Pcommandshops.clientTestDirectory=<path>` and
`-Pcommandshops.serverTestDirectory=<path>` override their game directories for
disposable verification runs. These properties also apply when preparing those
run configurations for an IDE launch. They do not change `runGameTestServer` or
the performance run directories.

### Detached administrator UI smoke test

Run the standalone client UI checks without connecting to a server:

```powershell
./gradlew.bat runClient '-Pcommandshops.clientTestDirectory=build/release-tests/admin-ui-client' '-Pcommandshops.adminUiSmokeTest=true'
```

`ClientAdminUiSmokeTest` checks detached forms, drafts, help, localization,
navigation, asynchronous reply handling and rendering, then captures the
administrator views and exits. Require `CLIENT_ADMIN_UI_SMOKE_RESULT: PASS` in
the isolated client's `logs/latest.log`; also inspect the individual smoke
markers for Audit, scroll retention, live controls, localization and feedback.
Screenshots are written below that client's `screenshots` directory. This
scenario does not establish a multiplayer connection.

### Real multiplayer smoke test

`DedicatedMultiplayerSmokeTest` and `ClientMultiplayerSmokeTest` exercise one
real Minecraft client over TCP against an ordinary dedicated server. They cover
administrator snapshot/save, rejection of a stale revision and recovery, manual
stock changes/refills, visibility, Audit summary/journal, persisted administrator
history and the native shop-to-bank-to-shop transition.

For the conflicting edit, the server constructs a second `ServerPlayer` actor
named `SmokeSecondAdmin` and saves through `AdminConfigService`. This is a second
administrator identity acting on the server; it is not a second connected TCP
client or a second administrator screen.

Use two fresh disposable game directories. The server fixture writes a test
shop/bank, enables relevant audit events and grants operator level 2 to the
connecting client. It requires an explicit `release-smoke-isolated.txt` sentinel
in its game directory before creating the fixture. Do not point it at a normal
development or production game directory.

Example setup, from the repository root in PowerShell, after accepting the
Minecraft EULA for this isolated server:

```powershell
$smokeServerDir = Join-Path (Get-Location) 'build/release-tests/multiplayer-server'
$smokeClientDir = Join-Path (Get-Location) 'build/release-tests/multiplayer-client'
New-Item -ItemType Directory -Path $smokeServerDir, $smokeClientDir -Force | Out-Null
Set-Content -LiteralPath (Join-Path $smokeServerDir 'release-smoke-isolated.txt') -Value 'Disposable multiplayer verification fixture' -Encoding utf8
Set-Content -LiteralPath (Join-Path $smokeServerDir 'eula.txt') -Value 'eula=true' -Encoding ascii
@'
server-ip=127.0.0.1
server-port=25576
online-mode=false
level-name=release-smoke-world
view-distance=3
simulation-distance=3
'@ | Set-Content -LiteralPath (Join-Path $smokeServerDir 'server.properties') -Encoding ascii
```

Start the server in one terminal:

```powershell
./gradlew.bat runServer '-Pcommandshops.serverTestDirectory=build/release-tests/multiplayer-server' '-Pcommandshops.multiplayerSmokeServer=true'
```

Wait for `MULTIPLAYER_SERVER_READY`, then start the client in another terminal:

```powershell
./gradlew.bat runClient '-Pcommandshops.clientTestDirectory=build/release-tests/multiplayer-client' '-Pcommandshops.multiplayerSmokeClient=true' '-Pcommandshops.multiplayerSmokeAddress=127.0.0.1:25576' '-Pcommandshops.adminUiSmokeTest=false'
```

Keep this unauthenticated test server bound to localhost. The default smoke
address is `127.0.0.1:25576`; changing it requires matching server properties and
the `commandshops.multiplayerSmokeAddress` client property. Keep
`commandshops.dedicatedCompatibilityTests=false` for this scenario, and run the
detached administrator UI smoke separately.

The client connects from the title screen, performs its sequence and exits;
the server verifies the final saved price and persisted history after the client
disconnects, then stops. The client times out after 180 seconds and the server
after 240 seconds. Both success markers are required:

- Client `logs/latest.log`: `MULTIPLAYER_CLIENT_RESULT: PASS`.
- Server `logs/latest.log`: `MULTIPLAYER_SERVER_RESULT: PASS`.

Treat either `FAIL`, a missing marker or a timeout as a failed run even when the
JVM or Gradle exits with code zero. Check the two logs under the isolated
directories; a client PASS alone does not prove the server's final persistence
assertions passed. These opt-in helpers are part of the GameTest source set and
are excluded from the distributed addon JAR.

## 1.1.1 regression coverage

- `EconomyAuditLogServerTest` checks configuration defaults, exact balance records,
  console actors and independently enabled optional reward/native categories.
- `AuditQueryServiceServerTest` mixes legacy records and new financial events in
  active/ZIP logs, tests both transfer participants and rejects corrupt records
  without partially changing statistics.
- `CommandShopEconomyAuditGameTests` exercises real wrapped CobbleDollars
  commands, native buy/sell handlers and battle reward attribution, including
  amounts beyond 64 bits, clamped withdrawals, cancellation, income multipliers
  and duplicate exclusion for command shops.
- Empty-shop resolution and menu-transition GameTests cover filtered shops,
  native bank roundtrips, last-offer deletion, re-addition and stale purchases.
- The detached client smoke requires `CLIENT_EMPTY_SHOP_RESULT: PASS` and
  `CLIENT_ADMIN_AUDIT_RESULT: PASS` in addition to the overall UI marker. It tests
  native empty widgets both with and without optional metadata, all five financial
  event forms/defaults and recipient-profile navigation in English and French.
  This detached metadata check does not itself prove a client install without
  the addon; any separate absent-addon verification is documented in the release
  validation report.

## Performance harness

The performance harness is intentionally separate from the standard suites. It exists to provide reproducible before/after measurements, not hard CI gates.

### Perf commands

Run JUnit perf:

```powershell
./gradlew.bat test "-Pcommandshops.includePerfTests=true" --no-configuration-cache
```

Run GameTest perf:

```powershell
./gradlew.bat runPerfGameTestServer
```

Run both:

```powershell
./gradlew.bat test "-Pcommandshops.includePerfTests=true" --no-configuration-cache
./gradlew.bat runPerfGameTestServer
```

### Perf source layout

- `src/perf-common/java`
  - shared helpers for JUnit perf and GameTest perf
  - not part of the shipped mod jar
- `src/test/java`
  - JUnit perf entrypoints
- `src/gametest/java`
  - GameTest perf entrypoints

### Perf helpers

- `fr.cobbledollars.commandshops.perf.PerfHarness`
  - warmup + measured iterations
  - min / avg / median / p95 / max
  - markdown + json report output
- `fr.cobbledollars.commandshops.perf.BenchConfigSupport`
  - stages `bench-configs/heavy_shop_bank/perf_megastore`
  - cleans the staged runtime copy
  - uses `commandshops.projectDir` to find the repo root
- `fr.cobbledollars.commandshops.shop.CommandShopSessions#measureRefreshPlayerSession`
  - diagnostics-only entrypoint used by the perf harness
  - captures refresh sub-step timings without changing the standard `refreshPlayerSession` benchmark contract

### Perf reports

Reports are written to:

- `build/reports/perf/`

Current outputs include:

- `junit-runtime-v1.{md,json}`
- `gametest-v2-open-heavy-shop.{md,json}`
- `gametest-v2-refresh-heavy-shop.{md,json}`
- `gametest-v2-refresh-heavy-shop-breakdown.{md,json}`
- `gametest-v2-sell-heavy-bank.{md,json}`

Use them differently:

- `gametest-v2-refresh-heavy-shop`
  - use this for before/after commit comparisons
  - it is the stable top-level refresh benchmark
- `gametest-v2-refresh-heavy-shop-breakdown`
  - use this to explain where refresh time is spent inside one revision
  - current phases include `resolveSessionShop`, `createRuntimeData`, `refreshSessionShop`, `syncClientShopUiState`, and `updateSessionRefreshState`
  - do not treat this breakdown report as the primary historical comparison target

## Infrastructure decisions

### `src/test/java`

Both logic-focused tests and runtime server tests live here. The distinction is by fixture usage:

- no server fixture: logic-focused test
- `EphemeralTestServerProvider`: runtime server test

This keeps the build simple while still giving us two different styles of JUnit coverage.

### `src/gametest/java`

GameTests live in a dedicated source set and are wired into the mod only for test runs.

Important points:

- `CommandShopGameTestBootstrap` registers the test framework and commands
- `@EmptyTemplate` is used so we do not have to maintain `.nbt` structure files for these tests
- `runGameTestServer` uses `run-gametest` as its game directory
- `runPerfGameTestServer` uses `run-gametest-perf`

The separate game directories are intentional. They avoid pollution from `run/mods` and keep GameTests reproducible.

## Repo-specific rules

### Mutating global shop state

`ShopRegistry` is global state.

Any test that writes `shop.json` or `bank.json` must:

- use a unique temporary shop id or the dedicated benchmark id
- clean up the created folder
- reload or clear the registry when needed

In JUnit runtime tests this is handled with `@AfterEach` or explicit cleanup.  
In GameTests this is handled in `finally` blocks plus a `ShopRegistry.reload(...)` when needed.

### Mutating active sessions

`CommandShopSessions` uses static maps for active sessions and pending opens.

If a test opens menus or queues shops:

- isolate it in its own GameTest batch when it mutates shared state
- prefer a fresh mock player per test
- close or invalidate state by the end of the scenario when practical

### SavedData round-trips

`ShopVisibilityData` and `PlayerShopStockData` have private static `load(...)` methods.

For focused persistence tests, it is acceptable to use reflection to call `load(...)` directly. That keeps the test on the persistence contract instead of forcing a broader world-save harness.

## Helpers

### `TestRegistryAccess`

Location: `src/test/java/fr/cobbledollars/commandshops/shop/TestRegistryAccess.java`

Purpose:

- provide a lightweight `HolderLookup.Provider` for parsing tests

Use it when you need to call:

- `ShopFiles.parseShopFile(...)`
- `BankFiles.loadBankFile(...)`

without spinning up a server fixture.

### `CommandShopGameTestSupport`

Location: `src/gametest/java/fr/cobbledollars/commandshops/gametest/CommandShopGameTestSupport.java`

Purpose:

- execute commands as the server or as a player
- write/remove temporary `shop.json`
- write temporary local `bank.json`

Prefer using this helper instead of duplicating filesystem and command boilerplate in each GameTest.

## Choosing the right layer

Use this rule:

1. If it is pure logic or parsing, write a logic-focused JUnit test.
2. If it needs a real server bootstrap but not a world/player flow, write a runtime JUnit test.
3. If it must prove an actual flow works through commands, ticks, players, menus, or sessions, write a GameTest.
4. If it is performance-sensitive, place the measurement in the perf harness instead of the standard regression suite.

Examples:

- invalid `restock` JSON: logic-focused JUnit
- `ShopVisibilityData` save/load: logic-focused JUnit with reflective `load(...)`
- `/cdshops open` opening a real `ShopMenu`: GameTest
- heavy benchmark reload/parse timing: JUnit perf
- heavy benchmark `openShop` / `refreshPlayerSession` / `handleCustomSell`: GameTest perf

`CommandShopMenuTransitionGameTests` exercises the native bank-button packet order:
`CloseShopPacket`, vanilla container close, then `OpenBankPacket`. Direct calls to
`openCustomBank` while the shop menu is still open do not cover this flow. The
tests also cover a tick between those packets, bank-to-shop item returns, denied
access restoring the shop, and expiration of a closed session.

## Observations and constraints

These are important implementation observations, not theory.

### `EphemeralTestServerProvider` is not a world/player substitute

The NeoForge runtime JUnit fixture is useful for bootstrap-sensitive tests, but it is not a good replacement for a real GameTest world.

Observed limitations during implementation:

- do not assume `server.overworld()` is usable in this fixture
- do not assume a practical `ServerPlayer` context exists
- do not push player/menu/session benchmarks into this layer

This is why the JUnit perf harness was intentionally narrowed to:

- `ShopRegistry.reload(...)`
- `ShopFiles.parseShopFile(...)`
- `BankFiles.loadBankFile(...)`

And why the player-bound runtime measurements live in GameTests instead.

### NeoForge still collects the perf GameTests in standard runs

Even with `enabledByDefault = false`, the framework still discovers the perf GameTest classes during `runGameTestServer`.

To keep the standard suite clean:

- `runPerfGameTestServer` sets `commandshops.perfGametests=true`
- the perf GameTests return immediately when that property is absent

This means standard runs still see those test ids, but they do not execute the benchmark body outside the dedicated perf run.

### Perf results are informational, not CI gates

Do not attach hard timing thresholds to these measurements yet.

Reasons:

- machine variance
- JVM warmup variance
- mod bootstrap variance
- third-party mod load cost

Use the reports to compare before/after changes and to decide what to optimize next.

## Adding new tests

### For parsing / validation

- write the smallest JSON that exercises the rule
- assert the exact failure message when the message is stable and intentional
- keep the file inline in the test unless reuse becomes real

### For runtime JUnit tests

- use `EphemeralTestServerProvider`
- keep them focused on registry/bootstrap concerns
- do not assume a usable world or real player context exists
- if the assertion needs `SavedData.get(server)`, a real player, inventory menus, or session state, move it to a GameTest or convert it to a focused persistence test

### For GameTests

- use `GameTestPlayer` when a real server-side player is needed
- use `helper.startSequence()` for queued or delayed flows
- separate tests into batches when they mutate shared global state
- prefer direct assertions on menus, stock, registry state, or saved data over log inspection

### For perf JUnit tests

- mark them with `@Tag("perf")`
- keep them server/bootstrap focused
- prefer parsing/reload/config-heavy code here
- write reports through `PerfHarness.writeReport(...)`

### For perf GameTests

- keep them under the dedicated perf class or a dedicated perf package
- guard execution behind `commandshops.perfGametests`
- keep setup outside the measured loop whenever possible
- keep at least one functional assertion inside the measured scenario

## Current coverage summary

Current coverage is intentionally strongest on:

- registry loading/reloading
- config parsing invariants
- stock/restock logic
- visibility state
- command registration and selected command flows
- session open/bank/close transitions
- runtime offer precedence with a real player context
- administrator draft validation, delayed replies, localization and scroll retention
- Audit queries and administrator stock/configuration history
- the opt-in real client/dedicated-server administrator and menu-transition flow
- heavy benchmark reload/parse/open/refresh/sell measurements

Still lighter than ideal:

- full `buy` and `sell` end-to-end coverage across more edge cases
- client rendering across additional resolutions and resource packs
- simultaneous edits from two independently connected administrator clients

Those are the next logical additions when the test pass needs to be expanded again.


Audit browsing is covered by `AuditQueryServiceServerTest` (active/ZIP files,
exact currency, corruption warnings, filters, cache refresh and cursor pages),
`CommandShopAdminAuditGameTests` (async delivery, session nonce, permissions,
replay protection and pending permission revocation), and the opt-in
`ClientAdminAuditSmokeTest` (all six forms, details, links, cursors, unsaved drafts,
focused filters and English/French layout at two widths). The client smoke run
also saves three Audit screenshots alongside the ten existing administrator views.
