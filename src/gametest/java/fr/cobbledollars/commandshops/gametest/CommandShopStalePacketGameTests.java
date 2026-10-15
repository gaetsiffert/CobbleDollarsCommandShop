package fr.cobbledollars.commandshops.gametest;

import java.util.UUID;

import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.harmex.cobbledollars.common.network.handlers.server.OpenBankHandler;
import fr.harmex.cobbledollars.common.network.handlers.server.OpenShopHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenBankPacket;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenShopPacket;
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

@ForEachTest(idPrefix = "access_packets.", groups = "access", side = Dist.DEDICATED_SERVER)
public final class CommandShopStalePacketGameTests {
    private CommandShopStalePacketGameTests() {
    }

    @TestHolder(value = "revoked_session_packets_cannot_open_native_fallbacks", title = "Revoked session packets cannot open native fallbacks")
    @GameTest(batch = "access_packets.stale", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void revoked_session_packets_cannot_open_native_fallbacks(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_stale_access_packets";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            player.addTag("bank_access");
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            UUID merchant = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
            CommandShopSessions.openCustomBank(player, merchant);
            BankMenu bank = (BankMenu) player.containerMenu;
            bank.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));
            player.removeTag("bank_access");
            CommandShopSessions.refreshPlayerSession(player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "Revocation must close the bank before replay.");

            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "Stale bank packet must not open the native default bank.");
            OpenShopHandler.INSTANCE.handle(new OpenShopPacket(merchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "Stale shop packet must not open the native default shop.");
            helper.assertValueEqual(3, player.getInventory().items.stream()
                    .filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum(), "Replayed opens must not duplicate or lose deposits.");

            UUID nativeMerchant = UUID.randomUUID();
            helper.assertTrue(!CommandShopSessions.openCustomBank(player, nativeMerchant), "Unrelated native banks must retain their native handler.");
            helper.assertTrue(!CommandShopSessions.reopenCustomShop(player, nativeMerchant), "Unrelated native shops must retain their native handler.");
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(nativeMerchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu instanceof BankMenu, "The native bank handler must still open native banks.");
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "wrong_merchant_packets_preserve_active_custom_menu", title = "Wrong merchant packets preserve active custom menu")
    @GameTest(batch = "access_packets.wrong_merchant", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void wrong_merchant_packets_preserve_active_custom_menu(ExtendedGameTestHelper helper) throws Exception {
        String shopId = "gametest_wrong_access_packets";
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            prepareShop(helper, shopId);
            CommandShopSessions.openShop(player, ShopRegistry.getShop(shopId));
            ShopMenu original = (ShopMenu) player.containerMenu;
            UUID unknownMerchant = UUID.randomUUID();
            OpenBankHandler.INSTANCE.handle(new OpenBankPacket(unknownMerchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == original, "Wrong bank merchant must not replace a command shop with the native bank.");
            OpenShopHandler.INSTANCE.handle(new OpenShopPacket(unknownMerchant), helper.getLevel().getServer(), player);
            helper.assertTrue(player.containerMenu == original, "Wrong shop merchant must not replace the current command shop.");
        } finally {
            cleanup(helper, player, shopId);
        }
        helper.succeed();
    }

    private static void prepareShop(ExtendedGameTestHelper helper, String shopId) throws Exception {
        CommandShopGameTestSupport.cleanupShop(shopId);
        CommandShopGameTestSupport.writeShopJson(shopId, """
                { "categories": [{ "name": "Access", "offers": [
                  { "id": "stone", "match": { "include": [{ "item": "minecraft:stone" }] }, "price": 1 }
                ] }] }
                """);
        CommandShopGameTestSupport.writeLocalBankJson(shopId, """
                { "conditions": [{ "player_tags_all": ["bank_access"], "deny_message": "Bank access required" }],
                  "offers": [{ "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": 7 }] }
                """);
        ShopRegistry.reload(helper.getLevel().registryAccess());
    }

    private static void cleanup(ExtendedGameTestHelper helper, GameTestPlayer player, String shopId) throws Exception {
        player.closeContainer();
        CommandShopSessions.cleanupPlayer(player.getUUID());
        CommandShopGameTestSupport.cleanupShop(shopId);
        ShopRegistry.reload(helper.getLevel().registryAccess());
    }
}
