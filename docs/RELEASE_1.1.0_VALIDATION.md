# Release 1.1.0 validation

Validation date: 2026-10-16, Windows 11, Java 21.0.10, Minecraft 1.21.1.

The development and verification runtime was updated from NeoForge 21.1.227 to
**21.1.262**, the newest stable 21.1 artifact in the
[official Maven metadata](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml)
at verification time. The distributed mod requires NeoForge **21.1.230** or newer.
The results below were obtained on **21.1.262**; they do not claim a separate test
run on the minimum supported version.
The compatibility baseline remains CobbleDollars 2.0.0+Beta-6.1+1.21.1,
Cobblemon 1.8.2 and Kotlin for Forge 5.12.0.

## Results

| Check | Result |
| --- | --- |
| Gradle build and GameTest compilation | PASS |
| Standard JUnit suite | 218 discovered: 217 passed, 1 skipped, 0 failures/errors |
| Dedicated GameTest server | 54/54 passed |
| Ordinary dedicated server with Easy NPC and CustomNPCs installed | 54/54 completed, 0 failures |
| Real client interface smoke | PASS; 13 administrator views rendered |
| Fresh generated defaults | PASS; 11 JSON files, 15 ordered condition blocks, no legacy condition objects |
| Separate TCP client/server smoke with existing configurations | PASS on both client and server |
| English/French resources | 729 keys each; matching keys and formatting placeholders |
| Distribution JAR | Version 1.1.0; metadata, ZIP integrity and test-code exclusion verified |

The skipped JUnit test creates symbolic links, which this Windows account cannot
create. File-size, combined-size, file-count and JSON-depth regressions were
executed. The GameTest count includes three performance entrypoints whose benchmark
bodies are intentionally inactive without the performance flag; this validation
does not claim new performance measurements. NPC command-source tests executed
with Easy NPC 7.15.0 and CustomNPCs 1.21.1.20251230 actually installed.

## Coverage

- Configuration startup/reload accepts a valid file larger than 2 MiB, with audit
  enabled or disabled. Editor transfer limits remain enforced. Audited reloads
  preserve the exact changed value, executor and final outcome.
- Existing suites cover access conditions, denial feedback, transaction rollback,
  stock persistence, menu transitions, permissions, session nonces and stale replies.
- Client checks cover offer order, feedback, localization, draft preservation,
  validation, live stock controls and action alignment. Audit coverage includes all
  six sections, history details, operation links, pagination and incoming/outgoing
  money help. Layout checks use English/French at two widths.
- Scroll checks cover independent configuration paths/files, Audit details/back,
  filter rebuilds, stock pages, loading forms, replies and screen reinitialization.
- The multiplayer harness uses one real TCP client and a second administrator
  represented by a server-side `ServerPlayer`. It checks snapshot/save, stale
  revision rejection and recovery, stock/refill, visibility, Audit summary/journal
  and native shop-bank-shop navigation. This is not a two-client TCP test.
- Nine existing shop directories and their 15 shop/bank JSON files were copied to
  the isolated multiplayer server. An additional test shop is created there. The
  original configuration files are not used as writable test fixtures.

## Corrections made during verification

The production correction separates server-local audit snapshots from the
administrator editor's bounded transfer format. Startup and `/cdshops reload` no
longer reject configurations solely because of editor size/count/depth limits.
Seven regression tests cover that separation.

The opt-in multiplayer harness was adjusted to respect the existing operation
rate limit, allow synchronization to settle and expect a fresh command-session
UUID after returning from the bank. It checks the configured category, item,
price and stock on both sides. Its player is protected from world hazards.
No production protection or menu-session behavior was relaxed for these tests.

The NPC suite requires generated default shops such as `general_store`. A first
attempt using only custom shops failed that fixture precondition. The recorded
passing run uses a fresh, isolated directory with generated default shops.

## Follow-up: generated defaults and editor conversions

The standard JUnit suite, all 54 GameTests in a fresh game directory and the real
client UI smoke were rerun after modernizing defaults. New access and visibility
conditions use ordered lists at all six levels. Generated shop/bank examples
preserve rule order and messages; existing files and legacy duplication remain
unchanged. Bank flattening accepts empty category conditions while protecting
restrictions, unknown fields and malformed values. The generated guide matches
the Markdown, including JSON stack escaping and line breaks.

This follow-up evidence is under `build/default-format-validation/`. The NPC and
TCP results above refer to the earlier release verification runs.

## Reproduction and evidence

See [TESTING.md](TESTING.md) for opt-in client, NPC and multiplayer instructions.
Both multiplayer PASS markers are required; process exit code zero alone is
insufficient. All smoke helpers remain outside the shipped JAR.

Local evidence is retained under `build/release-tests/results/`, alongside the
JUnit XML reports in `build/test-results/test/` and client captures in
`run/screenshots/`. Generated evidence is not committed.

Original validation artifact: `build/libs/cobbledollarscommandshops-neoforge-1.1.0.jar`.

SHA-256: `8a2ac06f38c9f81ef62a62a798528bc53b77c97673d4f9e00e3b0050cd5de0fe`.

## Follow-up: command access and tab descriptions (2026-10-19)

Added the enabled-by-default `commands.json` setting for non-admin shop opening.
The full JUnit suite discovered 219 tests: 218 passed, 1 skipped, no failures or
errors. All 55 GameTests passed, including saved/reloaded command access, permission
levels 0–4, stale parsed commands, administrator self-opening and targeted opens.

After adding English/French descriptions to the five tab header help markers,
compilation and both `AdminHelpCoverageTest` checks passed. The client, NPC and TCP
smoke results above refer to the earlier verification runs.

Current artifact: `build/libs/cobbledollarscommandshops-neoforge-1.1.0.jar`.

SHA-256: `17beb193befca7d15bd6c5d7c43ca3ee891dc03511a63efba6757b30bccf62e4`.
