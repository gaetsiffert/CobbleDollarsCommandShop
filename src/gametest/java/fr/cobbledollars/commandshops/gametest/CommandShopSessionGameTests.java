package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import fr.harmex.cobbledollars.common.network.packets.c2s.BuyPacket;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "sessions.", groups = "sessions", side = Dist.DEDICATED_SERVER)
public final class CommandShopSessionGameTests {
    private CommandShopSessionGameTests() {
    }

    @TestHolder(
            value = "open_command_opens_shop_menu_for_mock_player",
            title = "Open command opens shop menu",
            description = "Executes /cdshops open from a mock player and verifies that the queued shop open resolves to a real shop menu."
    )
    @GameTest(batch = "sessions.open_command", timeoutTicks = 160, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void open_command_opens_shop_menu_for_mock_player(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        try {
            int result = CommandShopGameTestSupport.executePlayerCommand(player, "cdshops open general_store");
            helper.assertValueEqual(1, result, "The open command did not report success.");
        } catch (CommandSyntaxException exception) {
            helper.fail("Open command GameTest failed: " + exception.getMessage());
            return;
        }

        helper.startSequence()
                .thenIdle(6)
                .thenExecute(() -> helper.assertTrue(player.containerMenu instanceof ShopMenu,
                        "The queued open command did not resolve to a shop menu."))
                .thenSucceed();
    }

    @TestHolder(
            value = "open_custom_bank_reuses_active_shop_session",
            title = "Open custom bank reuses active session",
            description = "Opens a shop session, switches to the custom bank through the public session API, and verifies the bank menu is active."
    )
    @GameTest(batch = "sessions.open_bank", timeoutTicks = 120, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void open_custom_bank_reuses_active_shop_session(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        CommandShopSessions.openShop(player, ShopRegistry.getShop("general_store"));
        helper.assertTrue(player.containerMenu instanceof ShopMenu, "Expected a shop menu after opening the session.");

        UUID merchantUuid = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
        helper.assertTrue(CommandShopSessions.openCustomBank(player, merchantUuid), "openCustomBank returned false for an active session.");
        helper.assertTrue(player.containerMenu instanceof BankMenu, "Expected the active session to switch to a bank menu.");
        helper.succeed();
    }

    @TestHolder(
            value = "disabling_visibility_closes_active_shop_session",
            title = "Disabling visibility closes active session",
            description = "Opens a shop session, disables the shop visibility, and verifies the active shop menu is closed."
    )
    @GameTest(batch = "sessions.visibility_close", timeoutTicks = 120, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void disabling_visibility_closes_active_shop_session(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        ShopVisibilityData visibilityData = ShopVisibilityData.get(helper.getLevel().getServer());

        try {
            visibilityData.enable("general_store");
            CommandShopSessions.openShop(player, ShopRegistry.getShop("general_store"));
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "Expected a shop menu before disabling visibility.");

            int result = CommandShopGameTestSupport.executeCommand(helper, "cdshops visibility general_store disable Closed");
            helper.assertValueEqual(1, result, "The visibility disable command did not report success.");
            helper.assertTrue(!visibilityData.status("general_store").enabled(), "general_store should be disabled after the command.");
            helper.assertTrue(!(player.containerMenu instanceof ShopMenu), "The active shop session should have been closed.");
        } catch (CommandSyntaxException exception) {
            helper.fail("Visibility close session GameTest failed: " + exception.getMessage());
            return;
        } finally {
            visibilityData.enable("general_store");
        }

        helper.succeed();
    }

    @TestHolder(
            value = "failed_custom_buy_rolls_back_balance_inventory_and_stock",
            title = "Failed custom buy rolls back state",
            description = "Forces a failure after inventory delivery during a custom buy and verifies that balance, inventory, and finite stock are restored."
    )
    @GameTest(batch = "sessions.buy_rollback", timeoutTicks = 160, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void failed_custom_buy_rolls_back_balance_inventory_and_stock(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.BUY_SHOP_ID);
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.BUY_SHOP_ID, """
                    {
                      "categories": [
                        {
                          "name": "Default",
                          "offers": [
                            {
                              "id": "entry_offer",
                              "match": {
                                "include": [
                                  { "item": "minecraft:emerald" }
                                ]
                              },
                              "count": 1,
                              "price": 5,
                              "stock": 3
                            }
                          ]
                        }
                      ]
                    }
                    """);
            CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");

            ShopDefinition shop = ShopRegistry.getShop(CommandShopGameTestSupport.BUY_SHOP_ID);
            helper.assertTrue(shop != null, "The buy rollback GameTest shop was not loaded.");

            PlayerShopStockData stockData = PlayerShopStockData.get(helper.getLevel().getServer());
            long nowMillis = System.currentTimeMillis();
            ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(stockData, player, nowMillis);
            ResolvedShopOffer offer = runtimeData.getResolvedOffer(0, 0);
            helper.assertTrue(offer != null, "The buy rollback runtime offer was not resolved.");
            helper.assertValueEqual(3, stockData.resolveStock(player.getUUID(), shop, offer, nowMillis),
                    "Finite stock should start at max stock.");

            PlayerExtensionKt.setCobbleDollars(player, BigInteger.valueOf(50L));
            player.getInventory().setItem(0, new ItemStack(Items.DIRT, 4));
            player.getInventory().setChanged();

            CommandShopSessions.openShop(player, shop);
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "Expected a shop menu after opening the buy rollback test session.");

            UUID merchantUuid = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
            CommandShopSessions.setTransactionTestHookForTesting((point, ignored) -> {
                if (point == CommandShopSessions.TransactionHookPoint.BUY_AFTER_INVENTORY_APPLY) {
                    throw new IllegalStateException("Forced buy rollback test failure.");
                }
            });

            BuyPacket packet = new BuyPacket(runtimeData.getRuntimeOffer(0, 0), 0, 0, 2, true, merchantUuid);
            helper.assertTrue(CommandShopSessions.handleCustomBuy(packet, helper.getLevel().getServer(), player),
                    "handleCustomBuy should report that the custom session packet was handled.");

            helper.assertTrue(BigInteger.valueOf(50L).equals(PlayerExtensionKt.getCobbleDollars(player)),
                    "The player's CobbleDollars balance should be restored after a failed buy commit.");
            helper.assertValueEqual(0, countItem(player, Items.EMERALD),
                    "The delivered items should be removed again after a failed buy commit.");
            helper.assertValueEqual(Items.DIRT, player.getInventory().getItem(0).getItem(),
                    "The original inventory contents should be restored after a failed buy commit.");
            helper.assertValueEqual(4, player.getInventory().getItem(0).getCount(),
                    "The original stack count should be restored after a failed buy commit.");
            helper.assertValueEqual(3, stockData.resolveStock(player.getUUID(), shop, offer, System.currentTimeMillis()),
                    "Finite stock should remain unchanged after a failed buy commit.");
        } catch (Exception exception) {
            helper.fail("Buy rollback session GameTest failed: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopSessions.cleanupAll();
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.BUY_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (Exception ignored) {
            }
        }

        helper.succeed();
    }

    @TestHolder(
            value = "selling_from_custom_bank_credits_player_and_keeps_unsellable_items",
            title = "Selling from custom bank credits player",
            description = "Builds a local shop and bank, sells one accepted stack plus one rejected stack, and verifies only the accepted items are converted to money."
    )
    @GameTest(batch = "sessions.sell_bank", timeoutTicks = 160, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void selling_from_custom_bank_credits_player_and_keeps_unsellable_items(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.SELL_SHOP_ID);
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.SELL_SHOP_ID, """
                    {
                      "categories": [
                        {
                          "name": "Default",
                          "offers": [
                            {
                              "id": "entry_offer",
                              "match": {
                                "include": [
                                  { "item": "minecraft:stone" }
                                ]
                              },
                              "count": 1,
                              "price": 1
                            }
                          ]
                        }
                      ]
                    }
                    """);
            CommandShopGameTestSupport.writeLocalBankJson(CommandShopGameTestSupport.SELL_SHOP_ID, """
                    [
                      {
                        "match": {
                          "include": [
                            { "item": "minecraft:emerald" }
                          ]
                        },
                        "price": 7
                      }
                    ]
                    """);
            CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");

            PlayerExtensionKt.setCobbleDollars(player, BigInteger.ZERO);
            CommandShopSessions.openShop(player, ShopRegistry.getShop(CommandShopGameTestSupport.SELL_SHOP_ID));
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "Expected a shop menu after opening the sell test session.");

            UUID merchantUuid = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
            helper.assertTrue(CommandShopSessions.openCustomBank(player, merchantUuid), "openCustomBank returned false for the sell test session.");
            helper.assertTrue(player.containerMenu instanceof BankMenu, "Expected the sell test session to switch to a bank menu.");

            BankMenu bankMenu = (BankMenu) player.containerMenu;
            bankMenu.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));
            bankMenu.getBankContainer().setItem(1, new ItemStack(Items.DIRT, 2));

            helper.assertTrue(CommandShopSessions.handleCustomSell(helper.getLevel().getServer(), player),
                    "handleCustomSell should succeed for an active custom bank session.");
            helper.assertTrue(bankMenu.getBankContainer().getItem(0).isEmpty(),
                    "Accepted items should be removed from the custom bank after a successful sell.");
            helper.assertValueEqual(Items.DIRT, bankMenu.getBankContainer().getItem(1).getItem(),
                    "Unsellable items should remain in the custom bank.");
            helper.assertValueEqual(2, bankMenu.getBankContainer().getItem(1).getCount(),
                    "Unsellable stack count should remain unchanged.");
            helper.assertTrue(BigInteger.valueOf(21L).equals(PlayerExtensionKt.getCobbleDollars(player)),
                    "The player did not receive the expected CobbleDollars amount.");
        } catch (Exception exception) {
            helper.fail("Sell bank session GameTest failed: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.SELL_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (Exception ignored) {
            }
        }

        helper.succeed();
    }

    @TestHolder(
            value = "failed_custom_sell_rolls_back_balance_and_bank_contents",
            title = "Failed custom sell rolls back state",
            description = "Forces a failure after partially clearing sold slots during a custom bank sale and verifies that balance and bank contents are restored."
    )
    @GameTest(batch = "sessions.sell_rollback", timeoutTicks = 160, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void failed_custom_sell_rolls_back_balance_and_bank_contents(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);

        try {
            CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.SELL_ROLLBACK_SHOP_ID);
            CommandShopGameTestSupport.writeShopJson(CommandShopGameTestSupport.SELL_ROLLBACK_SHOP_ID, """
                    {
                      "categories": [
                        {
                          "name": "Default",
                          "offers": [
                            {
                              "id": "entry_offer",
                              "match": {
                                "include": [
                                  { "item": "minecraft:stone" }
                                ]
                              },
                              "count": 1,
                              "price": 1
                            }
                          ]
                        }
                      ]
                    }
                    """);
            CommandShopGameTestSupport.writeLocalBankJson(CommandShopGameTestSupport.SELL_ROLLBACK_SHOP_ID, """
                    [
                      {
                        "match": {
                          "include": [
                            { "item": "minecraft:emerald" }
                          ]
                        },
                        "price": 7
                      }
                    ]
                    """);
            CommandShopGameTestSupport.executeCommand(helper, "cdshops reload");

            PlayerExtensionKt.setCobbleDollars(player, BigInteger.ZERO);
            CommandShopSessions.openShop(player, ShopRegistry.getShop(CommandShopGameTestSupport.SELL_ROLLBACK_SHOP_ID));
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "Expected a shop menu after opening the sell rollback test session.");

            UUID merchantUuid = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
            helper.assertTrue(CommandShopSessions.openCustomBank(player, merchantUuid),
                    "openCustomBank returned false for the sell rollback test session.");
            helper.assertTrue(player.containerMenu instanceof BankMenu, "Expected the sell rollback test session to switch to a bank menu.");

            BankMenu bankMenu = (BankMenu) player.containerMenu;
            bankMenu.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));
            bankMenu.getBankContainer().setItem(1, new ItemStack(Items.EMERALD, 2));
            bankMenu.getBankContainer().setItem(2, new ItemStack(Items.DIRT, 1));

            int[] clearedSlots = new int[1];
            CommandShopSessions.setTransactionTestHookForTesting((point, ignored) -> {
                if (point == CommandShopSessions.TransactionHookPoint.SELL_AFTER_SLOT_CLEAR && clearedSlots[0]++ == 0) {
                    throw new IllegalStateException("Forced sell rollback test failure.");
                }
            });

            helper.assertTrue(CommandShopSessions.handleCustomSell(helper.getLevel().getServer(), player),
                    "handleCustomSell should report that the custom bank session was handled.");

            helper.assertTrue(BigInteger.ZERO.equals(PlayerExtensionKt.getCobbleDollars(player)),
                    "The player's CobbleDollars balance should be restored after a failed sell commit.");
            helper.assertValueEqual(Items.EMERALD, bankMenu.getBankContainer().getItem(0).getItem(),
                    "The first sold stack should be restored after a failed sell commit.");
            helper.assertValueEqual(3, bankMenu.getBankContainer().getItem(0).getCount(),
                    "The first sold stack count should be restored after a failed sell commit.");
            helper.assertValueEqual(Items.EMERALD, bankMenu.getBankContainer().getItem(1).getItem(),
                    "The second sold stack should still be present after a failed sell commit.");
            helper.assertValueEqual(2, bankMenu.getBankContainer().getItem(1).getCount(),
                    "The second sold stack count should remain unchanged after a failed sell commit.");
            helper.assertValueEqual(Items.DIRT, bankMenu.getBankContainer().getItem(2).getItem(),
                    "Unsellable stacks should remain untouched after a failed sell commit.");
            helper.assertValueEqual(1, bankMenu.getBankContainer().getItem(2).getCount(),
                    "Unsellable stack count should remain unchanged after a failed sell commit.");
        } catch (Exception exception) {
            helper.fail("Sell rollback session GameTest failed: " + exception.getMessage());
            return;
        } finally {
            try {
                CommandShopSessions.cleanupAll();
                CommandShopGameTestSupport.cleanupShop(CommandShopGameTestSupport.SELL_ROLLBACK_SHOP_ID);
                ShopRegistry.reload(helper.getLevel().registryAccess());
            } catch (Exception ignored) {
            }
        }

        helper.succeed();
    }

    private static int countItem(GameTestPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().armor) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
