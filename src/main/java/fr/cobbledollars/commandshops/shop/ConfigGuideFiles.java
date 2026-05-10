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

                This file explains the full configuration format used by the mod.

                ## Directory Layout

                ```text
                config/cobbledollarscommandshops/
                  CONFIG_GUIDE.md
                  global_bank.json
                  shops/
                    general_store/
                      shop.json
                    blacksmith/
                      shop.json
                    explorer/
                      shop.json
                ```

                `global_bank.json` is the fallback bank for every custom shop.
                If `shops/<shop_id>/bank.json` exists, that file is used instead of the global bank for that shop.

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
                Example: `config/cobbledollarscommandshops/shops/blacksmith/shop.json`

                ```json
                {
                  "conditions": {
                    "player_tags_none": ["shop_banned"]
                  },
                  "categories": [
                    {
                      "name": "Weapons",
                      "offers": [
                        {
                          "id": "iron_sword",
                          "item": "minecraft:iron_sword",
                          "count": 1,
                          "price": 90
                        },
                        {
                          "id": "quest_blade",
                          "stack": "minecraft:diamond_sword[custom_name='{\\"text\\":\\"Quest Blade\\"}',custom_data={quest_id:\\"blade_alpha\\"}]",
                          "count": 1,
                          "price": 600,
                          "stock": 1,
                          "conditions": {
                            "advancements_any": ["minecraft:story/mine_diamond"]
                          }
                        }
                      ]
                    }
                  ]
                }
                ```

                ### Shop Fields

                - `conditions`: optional conditions applied to the whole shop
                - `categories`: required array
                - `categories[].name`: name displayed in the CobbleDollars UI
                - `categories[].conditions`: optional conditions applied to the whole category
                - `categories[].offers`: required array
                - `offers[].id`: unique stable id used for persistent stock
                - `offers[].item`: simple item id format
                - `offers[].stack`: exact stack format with Minecraft data components
                - `offers[].count`: amount inside one offer purchase, default `1`
                - `offers[].price`: CobbleDollars price
                - `offers[].stock`: maximum stock, omit or use `-1` for unlimited
                - `offers[].restock`: optional restock rule
                - `offers[].conditions`: optional conditions for a single offer

                Exactly one of `item` or `stack` must be present for each offer.

                The CobbleDollars amount selector buys multiple copies of the offer.  
                Example: if an offer uses `count: 32` for arrows and the player buys amount `2`, they receive `64` arrows.

                ## Bank File

                A bank can be flat:

                ```json
                {
                  "offers": [
                    {
                      "item": "minecraft:diamond",
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
                          "item": "minecraft:iron_ingot",
                          "price": 8
                        },
                        {
                          "stack": "minecraft:paper[custom_data={quest_id:\\"delivery_alpha\\"}]",
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
                - `offers[].item` or `offers[].stack`: item accepted by the bank
                - `offers[].price`: unit value paid to the player
                - `offers[].conditions`: optional conditions for a single bank entry

                Bank categories help organize large files, but CobbleDollars still receives a flat runtime bank.
                Bank offers do not support a `count` field.

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

                ## Notes

                - Player stock is persistent and stored server-side.
                - Restock uses real time, not in-game day time.
                - Config files are cached in memory.
                - Use `/cdshops reload` after editing files.
                - Custom shops only affect sessions opened through this addon.
                """;
    }
}
