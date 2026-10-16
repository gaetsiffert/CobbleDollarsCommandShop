package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobbledollars.commandshops.admin.runtime.AdminLiveService;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.players.ServerOpListEntry;
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
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        UUID playerUuid = player.getUUID();
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

            ShopDefinition.RuntimeShopOfferEntry runtimeEntry = shop.createRuntimeData(stockData, player, anchorMillis).getEntry(0, 0);
            helper.assertTrue(runtimeEntry != null, "Expected a visible runtime shop offer entry.");
            helper.assertTrue(runtimeEntry.restockPreview().hasNextRestock(),
                    "Runtime shop data should carry the pending restock preview for visible offers.");
            helper.assertValueEqual(anchorMillis + 1_000L, runtimeEntry.restockPreview().nextRestockAtMillis(),
                    "Runtime shop data did not preserve the expected restock timestamp.");
            helper.assertValueEqual(1, runtimeEntry.restockPreview().nextRestockAmount(),
                    "Runtime shop data did not preserve the expected restock amount.");

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
            value = "admin_restock_restores_target_stock",
            title = "Admin restock restores target stock",
            description = "Restocks one offer then the whole shop for a selected player without changing another player's stock."
    )
    @GameTest(batch = "stock.admin_restock", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void admin_restock_restores_target_stock(ExtendedGameTestHelper helper) {
        long anchorMillis = System.currentTimeMillis();
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        GameTestPlayer admin = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        var server = helper.getLevel().getServer();
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));

        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.STOCK_SHOP_ID, """
                    {"categories":[{"name":"Finite Stock","offers":[
                      {"id":"restock_bundle","match":{"include":[{"item":"minecraft:emerald"}]},
                       "price":15,"stock":3,"restock":{"type":"interval","amount":1,"every_seconds":3600}},
                      {"id":"other_bundle","match":{"include":[{"item":"minecraft:diamond"}]},"price":20,"stock":2}
                    ]}]}
                    """);
            CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");

            ShopDefinition shop = ShopRegistry.getShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
            helper.assertTrue(shop != null, "The stock GameTest shop was not loaded.");
            ShopOfferDefinition offerDefinition = shop.getOfferById("restock_bundle");
            helper.assertTrue(offerDefinition != null, "The stock GameTest offer was not loaded.");
            ResolvedShopOffer offer = offerDefinition.createResolvedOffers().getFirst();
            ResolvedShopOffer otherOffer = shop.getOfferById("other_bundle").createResolvedOffers().getFirst();

            PlayerShopStockData stockData = PlayerShopStockData.get(server);
            stockData.consumeStock(player.getUUID(), shop, offer, 2, anchorMillis);
            stockData.consumeStock(player.getUUID(), shop, otherOffer, 1, anchorMillis);
            stockData.consumeStock(admin.getUUID(), shop, offer, 1, anchorMillis);
            helper.assertValueEqual(1, stockData.resolveStock(player.getUUID(), shop, offer, System.currentTimeMillis()),
                    "Stock was not consumed correctly before the administrator action.");

            var offerResult = AdminLiveService.execute(admin, "restock_offer", shop.id(), player.getStringUUID(),
                    offer.sourceId(), offer.stockKey(), 0, true, "", false);

            helper.assertValueEqual(1, offerResult.get("affectedPlayers").getAsInt(), "Restocking must target the selected player only.");
            helper.assertValueEqual(3, stockData.resolveStock(player.getUUID(), shop, offer, System.currentTimeMillis()),
                    "Offer restocking did not restore the selected player's finite stock.");
            helper.assertValueEqual(1, stockData.resolveStock(player.getUUID(), shop, otherOffer, System.currentTimeMillis()),
                    "Offer restocking must preserve another offer's remaining stock.");
            helper.assertValueEqual(2, stockData.resolveStock(admin.getUUID(), shop, offer, System.currentTimeMillis()),
                    "Offer restocking must preserve the administrator's separate stock.");

            stockData.consumeStock(player.getUUID(), shop, offer, 2, System.currentTimeMillis());
            helper.assertValueEqual(1, stockData.resolveStock(player.getUUID(), shop, offer, System.currentTimeMillis()),
                    "The finite offer must be depleted before testing whole-shop restocking.");
            var shopResult = AdminLiveService.execute(admin, "restock_shop", shop.id(), player.getStringUUID(),
                    "", "", 0, true, "", false);
            helper.assertValueEqual(1, shopResult.get("affectedPlayers").getAsInt(), "Shop restocking must target the selected player only.");
            helper.assertValueEqual(3, stockData.resolveStock(player.getUUID(), shop, offer, System.currentTimeMillis()),
                    "Shop restocking did not restore the first finite offer.");
            helper.assertValueEqual(2, stockData.resolveStock(player.getUUID(), shop, otherOffer, System.currentTimeMillis()),
                    "Shop restocking did not restore the other finite offer.");
            helper.assertValueEqual(2, stockData.resolveStock(admin.getUUID(), shop, offer, System.currentTimeMillis()),
                    "Shop restocking must preserve the administrator's separate stock.");
        } catch (IOException | CommandSyntaxException exception) {
            helper.fail("Admin restock GameTest failed: " + exception.getMessage());
            return;
        } finally {
            server.getPlayerList().deop(admin.getGameProfile());
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.STOCK_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (IOException ignored) {
            }
        }

        helper.succeed();
    }
}
