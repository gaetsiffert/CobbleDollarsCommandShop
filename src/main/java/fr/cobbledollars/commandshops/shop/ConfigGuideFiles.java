package fr.cobbledollars.commandshops.shop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigGuideFiles {
    private static final Path GUIDE_FILE = ShopFiles.getConfigDirectory().resolve("CONFIG_GUIDE.md");

    private ConfigGuideFiles() {
    }

    public static Path getGuideFile() {
        return GUIDE_FILE;
    }

    public static void ensureDetailedGuideExists() throws IOException {
        Files.createDirectories(GUIDE_FILE.getParent());
        if (Files.exists(GUIDE_FILE)) {
            return;
        }
        Files.writeString(GUIDE_FILE, defaultGuideText());
    }

    public static String defaultGuideText() {
        return """
                # CobbleDollars Command Shops Configuration Guide

                This guide documents the current configuration format.
                The format is centered around `match.include` / `match.exclude`.
                Older examples that put `item`, `stack`, or `tag` directly on an offer are obsolete.

                ## Directory Layout

                ```text
                config/cobbledollarscommandshops/
                  CONFIG_GUIDE.md
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

                - `/cdshops open <shop>`
                - `/cdshops open <shop> <player>`
                - `/cdshops reload`
                - `/cdshops restock <shop> all <players>`
                - `/cdshops restock <shop> offer <offer> <players>`
                - `/cdshops stock <shop> <player>`
                - `/cdshops list`
                - `/cdshops where`

                ## Shop File

                Shop ids come from the folder name, not from JSON.
                Example: `config/cobbledollarscommandshops/shops/syntax_showcase/shop.json`

                ```json
                {
                  "deny_message": "This showcase is blocked for your current access profile.",
                  "conditions": {
                    "player_tags_none": ["syntax_showcase_banned"]
                  },
                  "categories": [
                    {
                      "name": "Fallback Pricing",
                      "offers": [
                        {
                          "id": "all_logs",
                          "match": {
                            "include": [
                              { "tag": "minecraft:logs" }
                            ]
                          },
                          "count": 16,
                          "price": 30
                        },
                        {
                          "id": "oak_log_vip",
                          "match": {
                            "include": [
                              { "item": "minecraft:oak_log" }
                            ]
                          },
                          "count": 16,
                          "price": 24,
                          "conditions": {
                            "player_tags_any": ["vip_shop"]
                          }
                        }
                      ]
                    }
                  ]
                }
                ```

                ### Shop Fields

                - `deny_message`: optional raw text message shown when the player cannot open the shop
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
                - `offers[].conditions`: optional conditions for a single offer

                The CobbleDollars amount selector buys multiple copies of the offer.  
                Example: if an offer uses `count: 32` for arrows and the player buys amount `2`, they receive `64` arrows.

                ## Bank File

                A bank can still be flat:

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
                  "conditions": {
                    "dimensions_any": ["minecraft:overworld"]
                  },
                  "categories": [
                    {
                      "name": "Ores",
                      "offers": [
                        {
                          "match": {
                            "include": [
                              { "item": "minecraft:iron_ingot" }
                            ]
                          },
                          "price": 8
                        },
                        {
                          "match": {
                            "include": [
                              { "stack": "minecraft:paper[custom_data={quest_id:\\\"delivery_alpha\\\"}]" }
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
                { "stack": "minecraft:paper[custom_data={quest_id:\\\"syntax_ticket\\\"}]" }
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
                [
                  {
                    "match": {
                      "include": [
                        { "tag": "minecraft:logs" }
                      ]
                    },
                    "price": 2
                  },
                  {
                    "match": {
                      "include": [
                        { "item": "minecraft:oak_log" }
                      ]
                    },
                    "price": 5
                  }
                ]
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
                      { "item": "minecraft:oak_log" }
                    ]
                  },
                  "price": 5,
                  "conditions": {
                    "player_tags_any": ["vip_shop"]
                  }
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
                  "conditions": {
                    "player_tags_all": ["vip_shop"],
                    "player_tags_none": ["shop_banned"],
                    "advancements_any": ["minecraft:story/mine_diamond"],
                    "dimensions_any": ["minecraft:overworld"],
                    "time_ranges_any": [
                      { "start_tick": 13000, "end_tick": 23000 }
                    ],
                    "scores_all": [
                      { "objective": "reputation", "min": 100 },
                      { "objective": "quest_stage", "equals": 3 }
                    ]
                  }
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
                - every condition type: `player_tags_all`, `player_tags_any`, `player_tags_none`, `advancements_all`, `advancements_any`, `dimensions_any`, `time_ranges_any`, `scores_all`
                - shop / category / offer conditions
                - local bank override

                ## Feedback Configuration

                `feedback.json` controls the fallback player feedback used when the client does not have this mod installed.
                In pure server-only mode it can use action bars, chat messages, and sounds.
                When a container screen such as a shop or bank is open, `action_bar` falls back to chat automatically because the action bar is hidden behind that UI.

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

                ## Optional Client Layer

                If the same jar is installed on the client, this mod adds a thin CobbleDollars UI layer without moving any gameplay authority to the client.

                With the client layer present:

                - shop and bank feedback is rendered directly on top of the CobbleDollars screen instead of using chat fallback
                - the selected offer can show the next restock amount and exact next restock time using server-provided timestamps

                Without the client layer:

                - custom shops still work normally
                - all validation, stock, bank rules, and conditions still stay server-side
                - feedback falls back to the channels configured in `feedback.json`

                ## Notes

                - Player stock is persistent and stored server-side.
                - Restock uses real time, not in-game day time.
                - Config files are cached in memory.
                - Use `/cdshops reload` after editing files.
                - Custom shops only affect sessions opened through this addon.
                - Exact `stack` matching includes data components.
                - `mod` matches item registry namespaces.
                - `match.exclude` only removes items from the current rule. A later rule can reintroduce them.
                - License terms are in the root `LICENSE` file and are bundled into the built jar.
                """;
    }
}
