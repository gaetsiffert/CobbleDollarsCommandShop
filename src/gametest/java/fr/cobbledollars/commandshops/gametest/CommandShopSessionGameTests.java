package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.util.UUID;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.gametest.framework.GameTest;
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
}
