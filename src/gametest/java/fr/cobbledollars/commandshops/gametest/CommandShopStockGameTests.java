package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "stock.", groups = "stock", side = Dist.DEDICATED_SERVER)
public final class CommandShopStockGameTests {
    private CommandShopStockGameTests() {
    }

    @TestHolder(
            value = "interval_restock_recovers_consumed_stock",
            title = "Interval restock recovers consumed stock",
            description = "Verifies finite stock consumption, preview, automatic interval restock, and cleanup of redundant stock state."
    )
    @GameTest(batch = "stock.interval_restock", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void interval_restock_recovers_consumed_stock(ExtendedGameTestHelper helper) {
        UUID playerUuid = UUID.fromString("4d7f4a62-8a17-4c45-a4fb-87e6d6c3b8d1");
        long anchorMillis = 1_700_000_000_000L;

        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.STOCK_SHOP_ID, """
                    {
                      "categories": [
                        {
                          "name": "Finite Stock",
                          "offers": [
                            {
                              "id": "restock_bundle",
                              "match": {
                                "include": [
                                  { "item": "minecraft:emerald" }
                                ]
                              },
                              "count": 1,
                              "price": 15,
                              "stock": 3,
                              "restock": {
                                "type": "interval",
                                "amount": 1,
                                "every_seconds": 1
                              }
                            }
                          ]
                        }
                      ]
                    }
                    """);
            CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");

            ShopDefinition shop = ShopRegistry.getShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
            helper.assertTrue(shop != null, "The stock GameTest shop was not loaded.");

            ShopOfferDefinition offerDefinition = shop.getOfferById("restock_bundle");
            helper.assertTrue(offerDefinition != null, "The stock GameTest offer was not loaded.");
            ResolvedShopOffer offer = offerDefinition.createResolvedOffers().getFirst();

            PlayerShopStockData stockData = PlayerShopStockData.get(helper.getLevel().getServer());
            helper.assertValueEqual(3, stockData.resolveStock(playerUuid, shop, offer, anchorMillis),
                    "Initial stock did not start at max stock.");

            stockData.consumeStock(playerUuid, shop, offer, 2, anchorMillis);
            helper.assertValueEqual(1, stockData.resolveStock(playerUuid, shop, offer, anchorMillis),
                    "Stock was not consumed correctly.");

            PlayerShopStockData.RestockPreview preview = stockData.previewNextRestock(playerUuid, shop, offer, anchorMillis);
            helper.assertTrue(preview.hasNextRestock(), "Expected a pending restock preview after consuming finite stock.");
            helper.assertValueEqual(anchorMillis + 1_000L, preview.nextRestockAtMillis(),
                    "The next restock timestamp is incorrect.");
            helper.assertValueEqual(1, preview.nextRestockAmount(),
                    "The next restock amount is incorrect.");

            helper.assertValueEqual(1, stockData.resolveStock(playerUuid, shop, offer, anchorMillis + 999L),
                    "Stock changed before the interval elapsed.");
            helper.assertValueEqual(2, stockData.resolveStock(playerUuid, shop, offer, anchorMillis + 1_000L),
                    "Stock did not recover after one interval.");
            helper.assertValueEqual(3, stockData.resolveStock(playerUuid, shop, offer, anchorMillis + 2_000L),
                    "Stock did not fully recover after two intervals.");
            helper.assertValueEqual(Long.MAX_VALUE, stockData.findNextRestockAtMillis(playerUuid, shop, anchorMillis + 2_000L),
                    "A redundant fully-restocked state should not keep a pending restock timestamp.");
        } catch (IOException | CommandSyntaxException exception) {
            helper.fail("Stock GameTest failed: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (IOException ignored) {
            }
        }

        helper.succeed();
    }

    @TestHolder(
            value = "restock_command_restores_target_stock",
            title = "Restock command restores target stock",
            description = "Consumes finite stock for a mock player, executes the restock command, and verifies the player stock is restored."
    )
    @GameTest(batch = "stock.restock_command", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void restock_command_restores_target_stock(ExtendedGameTestHelper helper) {
        long anchorMillis = 1_700_000_000_000L;
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.STOCK_SHOP_ID, """
                    {
                      "categories": [
                        {
                          "name": "Finite Stock",
                          "offers": [
                            {
                              "id": "restock_bundle",
                              "match": {
                                "include": [
                                  { "item": "minecraft:emerald" }
                                ]
                              },
                              "count": 1,
                              "price": 15,
                              "stock": 3,
                              "restock": {
                                "type": "interval",
                                "amount": 1,
                                "every_seconds": 1
                              }
                            }
                          ]
                        }
                      ]
                    }
                    """);
            CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");

            ShopDefinition shop = ShopRegistry.getShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
            helper.assertTrue(shop != null, "The stock GameTest shop was not loaded.");
            ShopOfferDefinition offerDefinition = shop.getOfferById("restock_bundle");
            helper.assertTrue(offerDefinition != null, "The stock GameTest offer was not loaded.");
            ResolvedShopOffer offer = offerDefinition.createResolvedOffers().getFirst();

            PlayerShopStockData stockData = PlayerShopStockData.get(helper.getLevel().getServer());
            stockData.consumeStock(player.getUUID(), shop, offer, 2, anchorMillis);
            helper.assertValueEqual(1, stockData.resolveStock(player.getUUID(), shop, offer, anchorMillis),
                    "Stock was not consumed correctly before the restock command.");

            int result = CommandShopGameTestSupport.executeCommand(
                    helper,
                    "cdshops restock " + CommandShopGameTestSupport.STOCK_SHOP_ID + " offer restock_bundle @a"
            );

            helper.assertValueEqual(1, result, "The restock command did not target the expected player count.");
            helper.assertValueEqual(3, stockData.resolveStock(player.getUUID(), shop, offer, anchorMillis),
                    "The restock command did not restore the player's finite stock.");
        } catch (IOException | CommandSyntaxException exception) {
            helper.fail("Restock command GameTest failed: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (IOException ignored) {
            }
        }

        helper.succeed();
    }
}
