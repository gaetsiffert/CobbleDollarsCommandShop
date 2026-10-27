# Release 1.1.1 validation

Validation date: 2026-10-27, Windows 11, Java 21.0.10, Minecraft 1.21.1,
NeoForge 21.1.262, CobbleDollars 2.0.0+Beta-6.1+1.21.1, Cobblemon 1.8.2.

## Results

- `gradlew.bat test runGameTestServer build --offline`: successful. JUnit
  discovered 227 tests: 226 passed, one Windows symbolic-link test skipped, no
  failures/errors. All 58 required GameTests passed, including the new native
  financial hooks and empty-shop scenarios.
- `gradlew.bat test build --offline`: successful after the final interface and
  translation adjustments, with the same JUnit results.
- Detached client smoke in `build/release-1.1.1-validation/client`: successful
  after final UI changes. Required `CLIENT_ADMIN_UI_SMOKE_RESULT: PASS`,
  `CLIENT_ADMIN_AUDIT_RESULT: PASS` and `CLIENT_EMPTY_SHOP_RESULT: PASS` markers
  were present. English/French forms rendered at widths 280 and 640, including
  all five financial categories/defaults, exact balance fields, recipient-profile
  navigation and native events without misleading command-shop item links.
- Generated configuration guide matches `docs/CONFIG_GUIDE.md`. Packaged metadata
  reports version 1.1.1; all production hooks and both translation catalogs are
  included. GameTest and performance helper classes are excluded from the JAR.
- A separate client/server TCP probe passed with the addon physically absent
  from the client. Both `NO_ADDON_CLIENT_RESULT: PASS` and
  `NO_ADDON_SERVER_RESULT: PASS` were observed; both JVMs exited normally.

## Regression scenarios

The server suite exercises actual wrapped CobbleDollars methods: a payment above
64 bits whose upstream integer return wraps to zero, refused/self payments,
clamped administrative withdrawals, unchanged balances, console actors,
multitarget operation correlation, canceled/multiplied battle rewards, native
commerce and command-shop duplicate exclusion. Generic balance setter writes
remain outside the tracking scope.

Audit tests mix version 1 shop records and all five financial categories across
active logs and ZIP archives. They verify exact amounts, participant/actor
filters, separate totals and rejection of inconsistent transfers or malformed
item names before any statistics change. Transfer/admin categories default on;
battle/native categories default off, can be enabled independently and respect
the master switch.

Empty-shop tests cover initially absent/empty categories, filtered/denied offers,
bank roundtrips, removing/re-adding the final offer while open, stale purchases
without debit and preservation of sold-out offers. The detached client also
checks native widgets with and without optional addon metadata.

## Client without the addon

A disposable probe was compiled with the existing Java/dependency classpaths
under `build/release-1.1.1-validation/no-addon-probe/`; its source, compiled classes
and launch arguments are retained there and excluded from the shipped JAR.
The client loaded only Minecraft, NeoForge, Kotlin for Forge, Cobblemon,
CobbleDollars and a test probe with no addon imports. Assertions confirmed the
addon was absent both from `ModList` and the client classloader.

The client connected over TCP to an isolated server on `127.0.0.1:25579` running
the production addon classes. It verified no default objects, no selected offer
and a disabled buy action in the empty shop, a native bank-to-shop roundtrip,
then live first-offer addition and last-offer removal. The server independently
verified both changes and the roundtrip. Each side reported PASS and stopped.

## Follow-up: empty bank highlights

Replaced the empty ingredient sentinel, which matched empty inventory slots,
with a native-codec-compatible contradictory component rule. The logical bank
remains empty. The optional client's native accepted-item fallback now excludes
templates that do not satisfy their own bank rule.

`gradlew.bat test runGameTestServer build --offline` passed again: 226 JUnit
tests passed, the same Windows symbolic-link test was skipped, and all 58
GameTests passed. The network roundtrip regression rejects empty stacks, every
registered item and modified sentinel candidates. The filtered-bank GameTest
also verifies unchanged deposits/balance and empty-to-visible-to-empty updates.

The detached client produced `CLIENT_EMPTY_BANK_RESULT: PASS`, verifying no
technical accepted items before/after optional metadata and normal bank filtering.
The updated addon-free TCP probe produced both client/server PASS markers after
checking empty and occupied inventory slots, then adding/removing a bank offer
while the bank stayed open. Both JVMs stopped normally. Direct probe launches
must use Java 21 explicitly; the system `java` currently resolves to Java 25.

Final follow-up evidence:

- `build/release-1.1.1-validation/empty-bank-server.log` and GameTest summary
  `run-gametest/logs/tests/cobbledollarscommandshops_gametests/summary_20261027164516.md`.
- `build/release-1.1.1-validation/empty-bank-client.log`.
- `build/release-1.1.1-validation/no-addon-probe/empty-bank-client-stdout.log` and
  `build/release-1.1.1-validation/no-addon-probe/empty-bank-server-stdout.log`.

## Evidence and artifact

- JUnit XML: `build/test-results/test/`.
- Server log: `run-gametest/logs/latest.log`; summary:
  `run-gametest/logs/tests/cobbledollarscommandshops_gametests/summary_20261027055600.md`.
- Final build log: `build/release-1.1.1-validation/build-gradle.log`.
- Final client log: `build/release-1.1.1-validation/client-final-gradle.log`;
  screenshots: `build/release-1.1.1-validation/client/screenshots/`.
- Absent-addon TCP logs:
  `build/release-1.1.1-validation/no-addon-probe/client-stdout.log` and
  `build/release-1.1.1-validation/no-addon-probe/server-stdout.log`.

Artifact: `build/libs/cobbledollarscommandshops-neoforge-1.1.1.jar`.

SHA-256: `3ff7f345a1bc9dc91cddbe2ef26c677f5a532f6de61c3cab9d8682c24016e217`.

The initial 1.1.0 state was pushed to `origin/neoforge-1-21-1` at
`d8ac5f2496082c23f6bd6402b8d0f7d058a8525a`. Version 1.1.1 was prepared on
`release/1.1.1`.
