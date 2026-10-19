# CobbleDollars Command Shops 1.1.0

In-game administration and audit history for Minecraft 1.21.1 / NeoForge.

## Added

- `/cdshops admin`: English/French administration panel for permission level 2,
  requiring the optional client addon.
- **Settings / Commands** toggle for non-admin `/cdshops open <shop>` access
  (enabled by default). Administrators and targeted NPC/server opens retain access.
- Full editing of shops, global/local banks, feedback and audit settings, including
  optional fields, nested entries, duplication, reordering, and shop/local-bank creation
  or deletion. Includes contextual help, validation, customization/reset controls,
  inherited feedback previews, quiet clicks and sound previews from drafts.
- Immediate shop enable/disable controls with optional denial messages, plus a
  **Stocks** tab to inspect, adjust or refill per-player stock by variant, offer or
  shop. Bulk changes for all online players require confirmation.
- Read-only **Audit** tab: summary, shop/player/item/offer statistics, money previews
  and transaction details, with period/event filters, search, sorting and pagination.
  Browses active logs and ZIP archives, including deleted shops and offline players;
  includes refresh, timestamps and warnings for disabled recording or unreadable entries.
- Manual stock history records the administrator, player, shop, variant and quantities
  before/after. Configuration history records the administrator, file, JSON path,
  exact before/after values and applied/failed/rollback outcomes. Reload history
  identifies the requester, not the editor of external files.
- Separate **stock changes** and **configuration changes** audit switches. Disabling
  configuration history or all logging is recorded before taking effect; the save
  is refused if that record cannot be written.

## Changed

- Ordered shop/bank access rules with individual denial messages; the first failed
  rule supplies the message. Legacy conditions and fallback messages remain supported
  and can be converted in the editor.
- Edits remain drafts until **Save**, with whole-configuration validation, conflict
  detection and grouped file application. Errors preserve drafts; discarding requires
  confirmation. Live controls and Audit browsing work independently of drafts.
- Navigation retains page/tab selection, sidebar search and scroll positions across
  pages, filters, responses and tab changes.

## Fixed

- Generated examples and new conditions use ordered rules; bank conversion accepts
  empty category conditions while protecting restricted categories.
- Large configs still load, reload and produce audit history beyond editor transfer limits.
- Shops preserve offer order after searches/stock updates and show unit prices from
  bundle price ÷ quantity, with up to three decimals.
- Restored shop-to-bank navigation with access checks. Disabled-shop feedback reaches
  the affected player, including NPC-triggered opens, through configured chat,
  action-bar and optional overlay messages.
- Compact list rows keep labels, help and actions together. Delayed or stale replies
  and disconnects no longer overwrite drafts or focused fields; conflicting server
  states can be loaded explicitly.

## Upgrade notes

- Update the server and administrator clients to **1.1.0**. Ordinary players still
  do not need the addon. Existing configs, access conditions, stock and logs remain compatible.
- **Removed commands:** `/cdshops list`, `/cdshops visibility`, `/cdshops stock`,
  `/cdshops restock`, `/cdshops stats`. Use `/cdshops admin` and update affected scripts
  or command blocks. Remaining commands: `open`, `admin`, `reload`, `where`.
- New history categories default to enabled and respect the global audit switch.
  Failed purchases/sales remain disabled by default; automatic stock changes,
  unchanged stock actions and access denials are excluded from history.
- Requires Minecraft **1.21.1**, NeoForge **21.1.230+** and CobbleDollars
  **2.0.0+Beta-6.1+1.21.1** or newer. Compatibility baseline: Cobblemon **1.8.2**,
  Kotlin for Forge **5.12.0**. NPC mods and JEI remain optional.
- For CobbleDollars Beta 5.1, keep addon **1.0.2**.
