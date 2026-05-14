# Testing Strategy

This repository uses three complementary test layers. Keep them all.

## Test layers

### 1. Logic-focused JUnit tests

Location: `src/test/java`

Use these when the code under test is mostly deterministic business logic:

- parsing and validation
- stock/restock calculations
- item match resolution
- small helper classes
- benchmark config invariants

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

Use these when the code needs a real NeoForge/Minecraft server bootstrap, but does not need a world-driven scenario or a real player.

This is the right layer for:

- `ShopRegistry`
- registry-backed parsing
- bootstrap-sensitive initialization

Examples in this repo:

- `ShopRegistryServerTest`

### 3. GameTests

Location: `src/gametest/java`

Use these for end-to-end server flows:

- commands
- queued opens
- active session lifecycle
- stock changes driven through command/session code
- menu transitions

GameTests are the highest-fidelity layer in the repo today. They run on a dedicated `gameTestServer` launch and exercise the real server loop.

Examples in this repo:

- `CommandShopRegistryGameTests`
- `CommandShopCommandGameTests`
- `CommandShopStockGameTests`
- `CommandShopSessionGameTests`
- `CommandShopResolutionGameTests`

## How the tasks map

Run all JUnit tests:

```powershell
./gradlew.bat test
```

Run all GameTests:

```powershell
./gradlew.bat runGameTestServer
```

Run the full test pass:

```powershell
./gradlew.bat test runGameTestServer
```

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

The separate game directory is intentional. It avoids pollution from `run/mods` and keeps GameTests reproducible.

## Repo-specific rules

### Mutating global shop state

`ShopRegistry` is global state.

Any test that writes `shop.json` or `bank.json` must:

- use a unique temporary shop id
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
2. If it needs a real server bootstrap but not a world or player, write a runtime JUnit test.
3. If it must prove an actual flow works through commands, ticks, players, menus, or sessions, write a GameTest.

Examples:

- invalid `restock` JSON: logic-focused JUnit
- `ShopVisibilityData` save/load: logic-focused JUnit with reflective `load(...)`
- `/cdshops open` opening a real `ShopMenu`: GameTest

## Adding new tests

### For parsing / validation

- write the smallest JSON that exercises the rule
- assert the exact failure message when the message is stable and intentional
- keep the file inline in the test unless reuse becomes real

### For runtime JUnit tests

- use `EphemeralTestServerProvider`
- keep them focused on registry/bootstrap concerns
- do not assume `server.overworld()` or a usable `ServerPlayer` exists in this fixture
- if the assertion needs `SavedData.get(server)`, a real player, or menu/session state, move it to a GameTest or convert it to a focused persistence test

### For GameTests

- use `GameTestPlayer` when a real server-side player is needed
- use `helper.startSequence()` for queued or delayed flows
- separate tests into batches when they mutate shared global state
- prefer direct assertions on menus, stock, registry state, or saved data over log inspection

## Current coverage summary

Current coverage is intentionally strongest on:

- registry loading/reloading
- config parsing invariants
- stock/restock logic
- visibility state
- command registration and selected command flows
- session open/bank/close transitions
- runtime offer precedence with a real player context

Still lighter than ideal:

- full `buy` and `sell` end-to-end flows
- client UI state/rendering
- packet-level assertions

Those are the next logical additions when the test pass needs to be expanded again.
