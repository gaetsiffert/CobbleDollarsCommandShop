# CobbleDollars Command Shops

<p align="center">
  <img src="art/CobbleDollarsCommandShop_no_background.png" alt="CobbleDollars Command Shops logo" width="320">
</p>

Server-side NeoForge addon for CobbleDollars on Minecraft `1.21.1`.
NeoForge `21.1.230` or newer is required.

Version `1.1.1` requires CobbleDollars `2.0.0+Beta-6.1+1.21.1` or newer. Its
configuration files, player stock, and optional client installation remain compatible.
Clients with the optional addon installed should also update it to `1.1.1`.
For CobbleDollars Beta 5.1, use addon version `1.0.2`.

The [1.1.1 changelog](docs/changelogs/CHANGELOG_1.1.1.md) covers empty-shop fixes
and expanded financial auditing. The [1.1.0 changelog](docs/changelogs/CHANGELOG_1.1.0.md) covers the new
administrator panel, Audit browser, manual change histories and upgrade notes.

This mod adds command-driven CobbleDollars shops with:
- persistent per-player stock
- configurable global or per-shop banks
- exact matching through `match.include` / `match.exclude` with `item`, `stack`, `tag`, and `mod`
- conditional visibility and access rules
- a complete in-game configuration editor with contextual English/French help
- runtime shop visibility controls and player stock management in the administrator panel
- optional purchase bonuses when buying enough bundles in one transaction
- structured audit logs for buys, sells, player transfers, administrative balance changes, visibility, manual stock changes and configuration edits
- optional audit categories for battle rewards and native CobbleDollars merchant commerce (disabled by default)
- a read-only Audit browser with filters, statistics and detailed transaction/change history
- hot config reload with `/cdshops reload`
- configurable player feedback through `feedback.json`, with or without the optional client layer
- optional client-side UI enhancements when the same jar is installed on the client
- built-in English and French localized feedback

It does not replace CobbleDollars, replace CobbleDollars NPCs, or move gameplay authority to the client. It only takes over sessions opened through this addon.

## Commands

Public commands:

- `/cdshops open <shop>`

Admin commands (`permission level 2`):

- `/cdshops admin` (requires this addon on the administrator's client)
- `/cdshops open <shop> <targets>`
- `/cdshops reload`
- `/cdshops where`

In **Settings / Commands**, disable **Allow players to open shops** to restrict
`/cdshops open <shop>` to administrators (permission level 2+). Enabled by default;
saved in `commands.json` as `allow_player_open`. Changes apply after **Save** or
`/cdshops reload`. Targeted opens, including NPC/server commands, still require level 2.

Version `1.1.0` replaces the former `list`, `visibility`, `stock`, `restock` and
`stats` commands with the administrator panel. Update any scripts or command blocks
that used those commands before upgrading.

## Config

Runtime config files are loaded from:

`config/cobbledollarscommandshops/`

On first server start, the mod generates the default config tree:

```text
config/cobbledollarscommandshops/
  CONFIG_GUIDE.md
  audit.json
  commands.json
  feedback.json
  global_bank.json
  shops/
    general_store/
      shop.json
    blacksmith/
      shop.json
    explorer/
      shop.json
    syntax_showcase/
      shop.json
      bank.json
```

The detailed format reference is available in the repository at:

`docs/CONFIG_GUIDE.md`

The same guide is also generated at runtime in:

`config/cobbledollarscommandshops/CONFIG_GUIDE.md`

The in-game administrator editor opens with `/cdshops admin`. It uses the same
form components, controls, palette, dropdowns and item picker as the Cobblemon
Expeditions administrator interface. It exposes every supported setting in
shops, global/local banks, feedback and audit, including optional settings that
are absent from the files. Nested lists support adding, editing, duplicating,
reordering and removing entries; shops and local banks can also be created or
deleted. Legacy bank formats are editable and can be converted to expose bank
conditions and categories.

Shop and bank access rules can be ordered and given individual denial messages.
Only the first failed rule's message is shown. The editor can convert legacy
condition objects while preserving their logic; category and offer conditions
continue to filter visibility.

Edits remain drafts until **Save**. Saving validates the complete configuration
on the server, checks for changes made since the editor loaded, writes the files
and reloads the active settings and shop sessions. Validation failures preserve
the draft. **Reload** reads the current server files and asks before discarding
unsaved changes. Permission level 2 is checked for all administrative transfers.
Other players can continue using the mod without installing its optional client
layer. The interface is available in English and French.

Inherited feedback settings show their effective defaults and can be customized
or reset explicitly. Sound previews play locally from the unsaved draft. Basic
field errors are shown before saving, and late server replies never silently
replace newer edits; their returned state can be loaded explicitly.

Files, nested configuration pages, stock pages and Audit lists/details retain their
own scroll positions. Switching tabs, returning from details or collapsing filters
keeps the relevant position; each tab also retains its selection and sidebar search.

The shop panel also provides an enable/disable button with an optional denial
message. The **Stocks** tab, after **Banks**, shows a player's
remaining stock and each offer's capacity. Administrators can adjust stock or
restock offers there. Visibility and stock actions apply immediately on the
server and refresh affected shop sessions; they are separate from configuration
drafts and do not require **Save**. Shop ids remain available in the shop list.
Visibility changes and manual stock management are available exclusively through
`/cdshops admin`, requiring permission level 2 and this addon on the administrator's client.

Audit logs are written to:

`logs/cobbledollarscommandshops/audit.jsonl`

The **Audit** tab, between **Stocks** and **Settings**, browses the current log
and ZIP archives without blocking the server tick. It includes a summary,
rankings by shop/player/item/offer, and a newest-first journal, with filters,
search, sorting, pagination and links between entity details and transactions.
**Refresh** reloads the data; the loaded time and incomplete-recording warnings
are shown in the panel. Audit settings remain under **Settings**. The former
`/cdshops stats` commands have been removed.
List help gives a quick preview of incoming and outgoing money for the selected
period and filters, without opening a detail page.

Manual administrator stock changes and configuration edits are recorded by default,
with independent switches under **Settings / Audit**. Stock entries identify the
administrator, affected player, offer variant and quantity before/after. Configuration
entries identify the administrator, file, JSON path and previous/new value. An
operation ID links prepared changes to their final applied, failed or rollback status.
Disabling configuration history or all audit logging is durably recorded before the
new setting takes effect on **Save**; toggling the draft alone changes no live setting.
If that recording fails, the save is refused. External file reloads identify who ran
the reload, rather than claiming to identify the person who edited the files.

Player transfers and administrative `give`/`remove`/`set` balance changes are
recorded by default. Battle rewards and native CobbleDollars purchases/sales have
separate switches and are **disabled by default**. Financial history shows actual
amounts and balances, includes both transfer participants, and keeps transfers
separate from money creation/destruction. Existing logs remain readable.

Empty custom shops display no default items, including for players without the
optional client addon.

Failed transactions are not recorded by default. Automatic refills and access denials
are not recorded; the browser cannot reconstruct unrecorded actions. Deleted shops
and offline players remain browsable through their recorded identifiers.

## Benchmark Configs

The repository also ships a standalone heavy benchmark pack in:

`bench-configs/heavy_shop_bank/`

It is intentionally kept out of the runtime config tree. Copy it into `config/cobbledollarscommandshops/shops/` only when you want to run manual performance tests.

License terms are in the root `LICENSE` file and are also bundled into the built jar.

## Build

`gradlew build`
