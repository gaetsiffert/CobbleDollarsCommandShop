package fr.cobbledollars.commandshops.gametest;

import java.util.UUID;

import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.harmex.cobbledollars.common.network.handlers.server.CloseShopHandler;
import fr.harmex.cobbledollars.common.network.handlers.server.OpenBankHandler;
import fr.harmex.cobbledollars.common.network.handlers.server.OpenShopHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.CloseShopPacket;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenBankPacket;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenShopPacket;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "menu_transition.", groups = "access", side = Dist.DEDICATED_SERVER)
public final class CommandShopMenuTransitionGameTests {
    private CommandShopMenuTransitionGameTests() {
    }

    @TestHolder(value = "native_bank_button_closes_shop_before_opening_bank", title = "Native bank button survives closing the shop")
    @GameTest(batch = "menu_transition.native_open", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void native_bank_button_closes_shop_before_opening_bank(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_button";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = closeShopLikeNativeBankButton(helper, player);
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            assertCustomBank(helper, player, merchant);
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "native_bank_button_survives_intervening_session_tick", title = "Native bank transition survives an intervening tick")
    @GameTest(batch = "menu_transition.tick_between_packets", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void native_bank_button_survives_intervening_session_tick(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_button_tick";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        UUID merchant;
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            merchant = closeShopLikeNativeBankButton(helper, player);
        } catch (Exception | Error failure) {
            cleanup(helper, player, shopId);
            throw failure;
        }
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        // The regular server hook also runs while these packets are separated.
                        CommandShopSessions.tick(helper.getLevel().getServer());
                        OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
                        assertCustomBank(helper, player, merchant);
                    } finally {
                        cleanup(helper, player, shopId);
                    }
                })
                .thenSucceed();
    }

    @TestHolder(value = "native_return_to_shop_returns_bank_deposits", title = "Native return to shop returns bank deposits")
    @GameTest(batch = "menu_transition.native_return", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void native_return_to_shop_returns_bank_deposits(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_return";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = closeShopLikeNativeBankButton(helper, player);
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            assertCustomBank(helper, player, merchant);
            BankMenu bank = (BankMenu) player.containerMenu;
            bank.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));

            // BankScreen's shop button sends only OpenShopPacket; it does not close first.
            OpenShopHandler.INSTANCE.handle(new OpenShopPacket(merchant), helper.getLevel().getServer(), player);
            assertCustomShop(helper, player);
            helper.assertValueEqual(3, countEmeralds(player), "Returning to the shop must return every unsold deposit.");
            helper.assertTrue(bank.getBankContainer().isEmpty(), "The replaced bank must retain no deposited items.");
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "native_bank_button_still_enforces_access_rules", title = "Native bank transition still enforces access rules")
    @GameTest(batch = "menu_transition.native_denied", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void native_bank_button_still_enforces_access_rules(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_denied";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = closeShopLikeNativeBankButton(helper, player);
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(!(player.containerMenu instanceof BankMenu), "A native bank transition must still enforce the bank access rules.");
            assertCustomShop(helper, player);
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "closed_session_expires_before_delayed_open_packet", title = "Closed sessions reject delayed open packets after expiry")
    @GameTest(batch = "menu_transition.expired", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void closed_session_expires_before_delayed_open_packet(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_expired";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        UUID merchant;
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            merchant = closeShopLikeNativeBankButton(helper, player);
        } catch (Exception | Error failure) {
            cleanup(helper, player, shopId);
            throw failure;
        }
        helper.startSequence()
                .thenIdle(22)
                .thenExecute(() -> {
                    try {
                        CommandShopSessions.tick(helper.getLevel().getServer());
                        OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
                        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                                "An expired command session must not open a custom or native fallback bank.");
                        OpenShopHandler.INSTANCE.handle(new OpenShopPacket(merchant), helper.getLevel().getServer(), player);
                        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                                "An expired command session must not open a custom or native fallback shop.");
                    } finally {
                        cleanup(helper, player, shopId);
                    }
                })
                .thenSucceed();
    }

    @TestHolder(value = "closed_session_cannot_replace_an_unrelated_container", title = "Closed sessions cannot replace an unrelated open container")
    @GameTest(batch = "menu_transition.unrelated_container", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void closed_session_cannot_replace_an_unrelated_container(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_unrelated_menu";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = closeShopLikeNativeBankButton(helper, player);
            player.openMenu(new SimpleMenuProvider(
                    (containerId, inventory, ignored) -> ChestMenu.threeRows(containerId, inventory),
                    Component.literal("Unrelated container")));
            helper.assertTrue(player.containerMenu instanceof ChestMenu, "The unrelated container fixture must open.");
            var unrelatedMenu = player.containerMenu;

            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == unrelatedMenu,
                    "A recently closed session must not replace an unrelated container with a bank.");
            OpenShopHandler.INSTANCE.handle(new OpenShopPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == unrelatedMenu,
                    "A recently closed session must not replace an unrelated container with a shop.");
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "lost_shop_access_during_transition_blocks_bank", title = "Lost root shop access blocks a pending bank transition")
    @GameTest(batch = "menu_transition.shop_access_revoked", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void lost_shop_access_during_transition_blocks_bank(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_bank_shop_revoked";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            CommandShopGameTestSupport.writeShopJson(shopId, """
                    { "conditions": [{ "player_tags_all": ["shop_access"], "deny_message": "Shop access required" }],
                      "categories": [{ "name": "Transition", "offers": [
                        { "id": "stone", "match": { "include": [{ "item": "minecraft:stone" }] }, "price": 1 }
                      ] }] }
                    """);
            ShopRegistry.reload(helper.getLevel().registryAccess());
            player.addTag("shop_access");
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = closeShopLikeNativeBankButton(helper, player);
            player.removeTag("shop_access");

            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                    "Losing root shop access must block opening its bank during a menu transition.");
            player.addTag("shop_access");
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            OpenShopHandler.INSTANCE.handle(new OpenShopPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                    "Restoring access must not revive the revoked session through a stale packet.");
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "wrong_merchant_cannot_replace_native_transition", title = "Wrong merchant cannot replace a pending native transition")
    @GameTest(batch = "menu_transition.wrong_merchant", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void wrong_merchant_cannot_replace_native_transition(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_native_transition_merchant";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = closeShopLikeNativeBankButton(helper, player);
            UUID unrelated = UUID.randomUUID();
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(unrelated), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                    "An unrelated merchant must not open a fallback bank during the native transition.");
            OpenShopHandler.INSTANCE.handle(new OpenShopPacket(unrelated), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu,
                    "An unrelated merchant must not open a fallback shop during the native transition.");
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            assertCustomBank(helper, player, merchant);
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    private static UUID closeShopLikeNativeBankButton(ExtendedGameTestHelper helper, GameTestPlayer player) {
        ShopMenu shop = (ShopMenu) player.containerMenu;
        UUID merchant = shop.getCobbleMerchant().getMerchantUUID();
        // ShopScreen.onClose sends the custom close, then AbstractContainerScreen closes
        // the vanilla container, and only afterwards the button sends OpenBankPacket.
        CloseShopHandler.INSTANCE.handle(new CloseShopPacket(merchant), helper.getLevel().getServer(), player);
        player.connection.handleContainerClose(new ServerboundContainerClosePacket(shop.containerId));
        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                "The native button closes the server shop before requesting the bank.");
        return merchant;
    }

    private static void assertCustomBank(ExtendedGameTestHelper helper, GameTestPlayer player, UUID merchant) {
        helper.assertTrue(player.containerMenu instanceof BankMenu, "The native bank button must open a bank menu after closing the shop.");
        BankMenu bank = (BankMenu) player.containerMenu;
        helper.assertTrue(merchant.equals(bank.getCobbleMerchant().getMerchantUUID()),
                "The opened bank must belong to the command session, not the native fallback.");
    }

    private static void assertCustomShop(ExtendedGameTestHelper helper, GameTestPlayer player) {
        helper.assertTrue(player.containerMenu instanceof ShopMenu, "The command shop must remain available after returning or a bank denial.");
        ShopMenu shop = (ShopMenu) player.containerMenu;
        helper.assertValueEqual("Transition", shop.getShop().get(0).getName(), "The menu must show the configured command shop.");
    }

    private static int countEmeralds(GameTestPlayer player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }

    private static void prepareShop(ExtendedGameTestHelper helper, String shopId) throws Exception {
        CommandShopGameTestSupport.cleanupShop(shopId);
        CommandShopGameTestSupport.writeShopJson(shopId, """
                { "categories": [{ "name": "Transition", "offers": [
                  { "id": "stone", "match": { "include": [{ "item": "minecraft:stone" }] }, "price": 1 }
                ] }] }
                """);
        CommandShopGameTestSupport.writeLocalBankJson(shopId, """
                { "conditions": [{ "player_tags_all": ["bank_access"], "deny_message": "Bank access required" }],
                  "offers": [{ "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": 7 }] }
                """);
        ShopRegistry.reload(helper.getLevel().registryAccess());
    }

    private static void cleanup(ExtendedGameTestHelper helper, GameTestPlayer player, String shopId) {
        try {
            player.closeContainer();
            CommandShopSessions.cleanupPlayer(player.getUUID());
            CommandShopGameTestSupport.cleanupShop(shopId);
            ShopRegistry.reload(helper.getLevel().registryAccess());
        } catch (Exception exception) {
            throw new RuntimeException("Failed to clean up menu transition GameTest.", exception);
        }
    }
}
