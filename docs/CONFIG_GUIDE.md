# CobbleDollars Command Shops Configuration Guide

This guide documents the current configuration format.
The format is centered around `match.include` / `match.exclude`.
Older examples that put `item`, `stack`, or `tag` directly on an offer are obsolete.

## Directory Layout

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
      bank.json
      shop.json
```

`global_bank.json` is the fallback bank for every custom shop.
If `shops/<shop_id>/bank.json` exists, that file is used instead of the global bank for that shop.
`syntax_showcase` is generated as a living example of every supported matching mechanic.

## Commands

Public commands:

- `/cdshops open <shop>`

Admin commands (`permission level 2`):

- `/cdshops admin` (requires this addon on the administrator's client)
- `/cdshops open <shop> <targets>`
- `/cdshops reload`
- `/cdshops where`

## Command Settings

In `/cdshops admin`, open **Settings / Commands** to control whether non-administrators
can use `/cdshops open <shop>`. The setting is stored in `commands.json`:

```json
{
  "allow_player_open": true
}
```

Enabled by default. When disabled, only permission level 2+ can open shops directly.
Administrators retain both command forms; targeted opens from NPCs, command blocks
and the server still require level 2 and work regardless of this setting. Shop and
bank access conditions still apply. Save in the editor or use `/cdshops reload`
after editing the file; connected players' command suggestions update automatically.

## Live Administration

The `/cdshops admin` panel lists all loaded shops. Its shop controls can enable
or disable a shop immediately and set an optional denial message while disabled.
These overrides persist on the server and disabling a shop closes its active
sessions.

The **Stocks** tab is after **Banks**. Select a shop and player
to inspect remaining stock, offer capacity, and restock information. Stock can
be adjusted directly, or reset to capacity for one offer or the selected shop.
These values are per-player remaining purchase bundles; changing them does not
change the configured capacity or automatic restock rule.

Visibility and stock actions take effect immediately and refresh affected shop
sessions. They do not edit configuration drafts and do not require **Save**.
These runtime controls are available exclusively through `/cdshops admin`.
The administrator must have permission level 2 and the optional client addon.

## Shop File

Shop ids come from the folder name, not from JSON.
Example: `config/cobbledollarscommandshops/shops/syntax_showcase/shop.json`

```json
{
  "conditions": [
    {
      "player_tags_none": [
        "syntax_showcase_banned"
      ],
      "deny_message": "This showcase is blocked for your current access profile."
    }
  ],
  "categories": [
    {
      "name": "Fallback Pricing",
      "offers": [
        {
          "id": "all_logs",
          "match": {
            "include": [
              {
                "tag": "minecraft:logs"
              }
            ]
          },
          "count": 16,
          "price": 30
        },
        {
          "id": "oak_log_vip",
          "match": {
            "include": [
              {
                "item": "minecraft:oak_log"
              }
            ]
          },
          "count": 16,
          "price": 24,
          "conditions": [
            {
              "player_tags_any": [
                "vip_shop"
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

### Shop Fields

- `deny_message`: optional legacy fallback text when a failed access rule has no custom message
- `conditions`: optional conditions applied to the whole shop
- `categories`: required array
- `categories[].name`: name displayed in the CobbleDollars UI
- `categories[].conditions`: optional conditions applied to the whole category
- `categories[].offers`: required array
- `offers[].id`: unique stable id used for persistent stock
- `offers[].match`: required match block
- `offers[].count`: amount inside one offer purchase, default `1`
- `offers[].price`: CobbleDollars price
- `offers[].stock`: maximum stock, omit or use `-1` for unlimited
- `offers[].restock`: optional restock rule
- `offers[].purchase_bonuses`: optional bonuses granted only when enough bundles are bought in one transaction
- `offers[].conditions`: optional conditions for a single offer

The CobbleDollars amount selector buys multiple copies of the offer.  
Example: if an offer uses `count: 32` for arrows and the player buys amount `2`, they receive `64` arrows.

Purchase bonus example:

```json
{
  "purchase_bonuses": [
    {
      "required_bundles": 10,
      "rewards": [
        { "item": "minecraft:gold_nugget", "count": 1 }
      ]
    }
  ]
}
```

In that example, the reward is granted only when the player buys `10` bundles in one purchase.
Buying `5` and then `5` does not trigger the bonus.
If an offer has finite stock, `required_bundles` cannot be greater than that stock.

## Bank File

New bank files use an object with either `offers` or `categories`.
A bank can use a direct offer list:

```json
{
  "offers": [
    {
      "match": {
        "include": [
          { "item": "minecraft:diamond" }
        ]
      },
      "price": 75
    }
  ]
}
```

Or grouped by categories:

```json
{
  "conditions": [
    {
      "dimensions_any": [
        "minecraft:overworld"
      ]
    }
  ],
  "categories": [
    {
      "name": "Ores",
      "offers": [
        {
          "match": {
            "include": [
              {
                "item": "minecraft:iron_ingot"
              }
            ]
          },
          "price": 8
        },
        {
          "match": {
            "include": [
              {
                "stack": "minecraft:paper[custom_data={quest_id:\"delivery_alpha\"}]"
              }
            ]
          },
          "price": 250
        }
      ]
    }
  ]
}
```

### Bank Fields

- `deny_message`: optional fallback text when a failed bank access rule has no custom message
- `conditions`: optional conditions for the whole bank
- `offers`: direct list of bank entries
- `categories`: optional category list instead of `offers`
- `categories[].name`: config-only label for organization
- `categories[].conditions`: optional conditions for the whole category
- `offers[].match`: required match block
- `offers[].price`: unit value paid to the player
- `offers[].conditions`: optional conditions for a single bank entry

Bank categories help organize large files, but CobbleDollars still receives a flat runtime bank.
Bank offers do not support a `count` field.
Older bank files containing only a root array remain readable; the editor can
wrap them in an object when bank-wide conditions or categories are needed.
New files already use the object format.

## Match Syntax

Every shop offer and bank entry must define:

```json
"match": {
  "include": [
    { "item": "minecraft:diamond" }
  ],
  "exclude": [
    { "item": "minecraft:coal" }
  ]
}
```

`include` is required and must contain at least one entry.  
`exclude` is optional and can contain zero or more entries.

Each entry inside `include` or `exclude` must define exactly one of:

- `item`
- `stack`
- `tag`
- `mod`

Examples:

```json
{ "item": "minecraft:oak_log" }
{ "stack": "minecraft:paper[custom_data={quest_id:\"syntax_ticket\"}]" }
{ "tag": "minecraft:logs" }
{ "mod": "minecraft" }
```

`item`, `tag`, `mod`, `include`, and `exclude` all accept multiple entries.

Example with multiple includes and excludes:

```json
{
  "match": {
    "include": [
      { "item": "minecraft:compass" },
      { "item": "minecraft:map" },
      { "item": "minecraft:spyglass" }
    ],
    "exclude": [
      { "item": "minecraft:map" }
    ]
  }
}
```

## Matching Priority

When several visible rules target the same concrete item, the mod resolves them with this priority:

1. `stack`
2. `item`
3. `tag`
4. `mod`

If two visible rules have the same priority, the first declared rule wins.

Example:

```json
{
  "offers": [
    {
      "match": {
        "include": [
          {
            "tag": "minecraft:logs"
          }
        ]
      },
      "price": 2
    },
    {
      "match": {
        "include": [
          {
            "item": "minecraft:oak_log"
          }
        ]
      },
      "price": 5
    }
  ]
}
```

Here `oak_log` takes the explicit `item` rule at `5`, while the other logs stay at `2`.

## Fallback vs Strict Override

**Fallback override**: do not exclude the specific item from the broad rule.

```json
{
  "match": {
    "include": [
      { "tag": "minecraft:logs" }
    ]
  },
  "price": 2
}
```

```json
{
  "match": {
    "include": [
      {
        "item": "minecraft:oak_log"
      }
    ]
  },
  "price": 5,
  "conditions": [
    {
      "player_tags_any": [
        "vip_shop"
      ]
    }
  ]
}
```

If `vip_shop` is missing, `oak_log` falls back to the broad `tag` rule.

**Strict override**: explicitly exclude the specific item from the broad rule.

```json
{
  "match": {
    "include": [
      { "tag": "minecraft:logs" }
    ],
    "exclude": [
      { "item": "minecraft:oak_log" }
    ]
  },
  "price": 2
}
```

In that case, `oak_log` only exists if another rule reintroduces it.

## Conditions

Conditions can be used on shops, shop categories, shop offers, banks, bank categories, and bank offers.

### Ordered Access Rules and Denial Messages

New conditions use an ordered array at every level. Each rule contains exactly
one condition family. Shop-wide and bank-wide access rules can also have an
optional `deny_message`:

```json
{
  "conditions": [
    {
      "player_tags_all": ["vip_shop"],
      "deny_message": "This access is reserved for VIP players."
    },
    {
      "scores_all": [{ "objective": "badges", "min": 4 }],
      "deny_message": "You need at least 4 badges."
    },
    {
      "time_ranges_any": [{ "start_tick": 13000, "end_tick": 23000 }],
      "deny_message": "Come back at night."
    }
  ]
}
```

Rules are checked from top to bottom. The first failed rule blocks access and
produces one message. Its custom text takes precedence over the root
`deny_message` fallback. If both are absent or blank, the failed condition family
has a default message translated into English or French (English fallback).
Custom messages are literal text and are not translated automatically.

Repeat a family in separate rules to give each required tag, advancement or score
its own message. Keep alternatives such as `player_tags_any` together: the group
only fails when none of its alternatives passes. All rules in the outer array
must pass. Empty arrays add no restriction; each individual rule must contain a
nonempty condition family.

The administrator screen lets you edit each rule's message and change the rule
order with the arrows. Existing object-format conditions remain supported and
can be converted to ordered rules in the editor. Existing files are not rewritten
automatically. Newly generated examples and conditions added in the panel already
use ordered rules, so they do not need that conversion. Legacy evaluation order remains:
`player_tags_all`, `player_tags_any`, `player_tags_none`, `advancements_all`,
`advancements_any`, `dimensions_any`, `time_ranges_any`, `scores_all`.

Conversion separates independent requirements into individual rules and keeps
alternative groups together. Existing root fallback messages are retained.

Shop conditions are checked before the effective bank's conditions. A failed bank
rule keeps the shop open instead of opening an empty bank. Bank access is checked
again before selling and while refreshing an open menu; losing access closes the
bank normally and returns deposited items. Local banks override the global bank,
including its access rules.

Category and offer conditions filter visibility and pricing candidates. They do
not block opening the shop or bank, and their rule messages are not displayed.

### Condition Families

Supported condition keys:

- `player_tags_all`
- `player_tags_any`
- `player_tags_none`
- `advancements_all`
- `advancements_any`
- `dimensions_any`
- `time_ranges_any`
- `scores_all`

Example:

```json
{
  "conditions": [
    {
      "player_tags_all": [
        "vip_shop"
      ]
    },
    {
      "player_tags_none": [
        "shop_banned"
      ]
    },
    {
      "advancements_any": [
        "minecraft:story/mine_diamond"
      ]
    },
    {
      "dimensions_any": [
        "minecraft:overworld"
      ]
    },
    {
      "time_ranges_any": [
        {
          "start_tick": 13000,
          "end_tick": 23000
        }
      ]
    },
    {
      "scores_all": [
        {
          "objective": "reputation",
          "min": 100
        }
      ]
    },
    {
      "scores_all": [
        {
          "objective": "quest_stage",
          "equals": 3
        }
      ]
    }
  ]
}
```

Conditions are evaluated before priority resolution.  
A more specific rule only wins if its own conditions pass.

### Time Range Fields

- `start_tick`: start of the allowed in-game range, between `0` and `23999`
- `end_tick`: end of the allowed in-game range, between `0` and `23999`
- `start_tick` and `end_tick` must be different

Ranges use Minecraft day time:

- sunrise: around `0`
- day: roughly `1000` to `12000`
- sunset: around `12000`
- night: roughly `13000` to `23000`

Wrap-around ranges are supported.  
Example: `{ "start_tick": 13000, "end_tick": 2000 }` matches late night through dawn.

### Score Condition Fields

- `objective`: scoreboard objective name
- `min`: optional minimum value
- `max`: optional maximum value
- `equals`: optional exact value

A score condition must define at least one of `min`, `max`, or `equals`.

## Restock Rules

Interval restock:

```json
{
  "restock": {
    "type": "interval",
    "amount": 1,
    "every_seconds": 600
  }
}
```

For `interval` restock, the recovery timer starts when stock first drops below full.
Later purchases do not restart that timer while the offer is still below its maximum stock.

Daily reset:

```json
{
  "restock": {
    "type": "daily_reset",
    "hour": 4,
    "minute": 0,
    "time_zone": "Europe/Paris"
  }
}
```

## Generated Examples

By default the mod generates:

- practical starter shops: `general_store`, `blacksmith`, `explorer`
- when Cobblemon is loaded: `trainer_supply`, `breeder_corner`, `night_market`
- a practical fallback bank: `global_bank.json`
- a dedicated showcase shop with a local bank: `shops/syntax_showcase/`

`syntax_showcase` is where you can find examples of:

- `item`
- `stack`
- `tag`
- `mod`
- multiple `include`
- multiple `exclude`
- fallback override
- strict override
- purchase bonuses with `required_bundles`
- every condition type: `player_tags_all`, `player_tags_any`, `player_tags_none`, `advancements_all`, `advancements_any`, `dimensions_any`, `time_ranges_any`, `scores_all`
- shop / category / offer conditions
- local bank override

## Feedback Configuration

`feedback.json` controls player feedback whether or not the client has this mod installed.
Every configured `chat` or `action_bar` channel is sent, and `sound` plays the configured sound once.
With the optional client layer, a message box also appears near the top-left of the screen whenever a text channel is enabled; it does not replace chat or the action bar.
Minecraft may hide the action bar while a shop or bank screen is open. For clients without the optional layer, an `action_bar` message is also sent to chat while a container is open so that it remains visible.
An empty channel list disables both text displays and sound for that event.

```json
{
  "buy_success": {
    "channels": ["action_bar", "sound"],
    "sound": {
      "id": "minecraft:entity.experience_orb.pickup",
      "volume": 0.25,
      "pitch": 1.8
    }
  },
  "buy_failure": {
    "channels": ["action_bar", "sound"],
    "sound": "minecraft:entity.villager.no"
  },
  "sell_success": {
    "channels": ["action_bar", "sound"],
    "sound": "minecraft:entity.experience_orb.pickup"
  },
  "sell_failure": {
    "channels": ["action_bar", "sound"],
    "sound": "minecraft:entity.villager.no"
  },
  "shop_denied": {
    "channels": ["chat", "sound"],
    "sound": "minecraft:entity.villager.no"
  }
}
```

Supported feedback channels:

- `action_bar`
- `chat`
- `sound`

Sound values can be either:

- a string resource id such as `"minecraft:entity.villager.no"`
- or an object with `id`, `volume`, and `pitch`

Built-in feedback text uses the player's language when the client supports this mod, or the normal Minecraft translation system for server-side messages. This project ships `en_us` and `fr_fr`.

## Audit Configuration

`audit.json` controls server-side audit logging.
It can disable the whole feature, enable or disable individual event types, and decide whether each appended JSON line is flushed immediately.

```json
{
  "enabled": true,
  "flush_each_entry": true,
  "events": {
    "buy_success": true,
    "buy_failure": false,
    "sell_success": true,
    "sell_failure": false,
    "visibility_changed": true,
    "stock_changed": true,
    "config_changed": true
  }
}
```

Audit entries are appended as JSON Lines to `logs/cobbledollarscommandshops/audit.jsonl`.
The **Audit** tab in `/cdshops admin` reads the active `audit.jsonl` file and rotated `.jsonl.zip` archives asynchronously. It provides a summary, searchable and sortable shop/player/item/offer statistics, and a newest-first transaction journal. All views support period and recorded-identifier filters, pagination, detail pages, links to related transactions, and explicit refresh. The audit browser is read-only and preserves configuration drafts. The former `/cdshops stats` commands have been removed.
The current schema logs:

- `buy_success`
- `buy_failure`
- `sell_success`
- `sell_failure`
- `visibility_changed`
- `stock_changed`
- `config_changed`

`stock_changed` records only real manual administrator stock changes after they are saved:
setting the remaining stock, refilling offer variants, and refilling a shop. Each entry
identifies the administrator, affected player, shop, source offer, exact stock variant,
item, and remaining stock before/after. Bulk changes share an `operation_id`.
Purchases, automatic refills, rejected requests and unchanged quantities do not create
manual stock events.

`config_changed` records saved administrator edits and reloads, including creations
and deletions. A `prepared` entry describes each changed file and JSON pointer with
its exact `before`/`after` values and presence flags (an absent value differs from JSON
null). One terminal entry with the same `operation_id` reports `applied`, `failed`,
`rolled_back`, `rollback_failed` or `activation_failed`. Prepared changes alone do not
confirm activation. A failed reload activation may have already applied some settings;
it does not guarantee that the previous configuration was restored.
The journal's **View operation** action shows all records belonging to that operation.
Only `applied` operations contribute to the configuration-change summary count.

Both types default to enabled and can be changed independently in **Settings / Audit**.
Configuration history captures the old or new enabled policy: turning off
`events.config_changed`, or the audit master switch, is written and forced to disk
before an administrator save changes configuration files (or before a reload activates
external edits), and its final outcome is retained even after recording is disabled. A failure to write that initial history rejects
the save before changes are made. Unchanged configuration saves produce no events.
For `source: "reload"`, the recorded actor is the person running the reload, not the
unknown author of external file edits. If files contain external changes that have not
yet been reloaded, administrator saves are rejected and their drafts are kept; run
`/cdshops reload` first. Oversized JSON values retain a bounded preview,
the original byte size, SHA-256 fingerprint and explicit truncation flags.

## Optional Client Layer

If the same jar is installed on the client, this mod adds a thin CobbleDollars UI layer without moving any gameplay authority to the client.

With the client layer present:

- shop and bank feedback appears in an additional message box near the top-left of the screen, alongside the channels configured in `feedback.json`
- the selected offer can show the next restock amount and exact next restock time using server-provided timestamps

Without the client layer:

- custom shops still work normally
- all validation, stock, bank rules, and conditions still stay server-side
- feedback uses the channels configured in `feedback.json`, with chat also showing action-bar messages while a container is open

## Notes

- Player stock is persistent and stored server-side.
- Restock uses real time, not in-game day time.
- Config files are cached in memory.
- Use `/cdshops reload` after editing files.
- Custom shops only affect sessions opened through this addon.
- Exact `stack` matching includes data components.
- `mod` matches item registry namespaces.
- `match.exclude` only removes items from the current rule. A later rule can reintroduce them.
- Shop visibility overrides are stored server-side and can be controlled from the administrator shop panel.
- Audit events enabled in `audit.json` are appended to `logs/cobbledollarscommandshops/audit.jsonl`.
- License terms are in the root `LICENSE` file and are bundled into the built jar.
