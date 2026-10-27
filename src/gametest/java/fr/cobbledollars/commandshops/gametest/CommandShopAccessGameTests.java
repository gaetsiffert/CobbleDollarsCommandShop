package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
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

@ForEachTest(idPrefix = "access.", groups = "access", side = Dist.DEDICATED_SERVER)
public final class CommandShopAccessGameTests {
    private static final String SHOP_ID = "gametest_access_rules";
    private static final String SHOP_OFFERS = """
            "categories": [{ "name": "Access", "offers": [{
              "id": "stone", "match": { "include": [{ "item": "minecraft:stone" }] }, "price": 1
            }] }]
            """;
    private static final String BANK_RULES = """
            {
              "conditions": [
                { "player_tags_all": ["bank_vip"], "deny_message": "Bank VIP required" },
                { "player_tags_all": ["bank_badges"], "deny_message": "Bank badges required" }
              ],
              "offers": [{ "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": 7 }]
            }
            """;

    private CommandShopAccessGameTests() {
    }

    @TestHolder(value = "first_shop_failure_controls_access", title = "First failed shop rule controls access")
    @GameTest(batch = "access.shop_rules", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void first_shop_failure_controls_access(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            ShopDefinition shop = loadShop(helper, """
                    "deny_message": "Old fallback",
                    "conditions": [
                      { "player_tags_all": ["vip"], "deny_message": "VIP required" },
                      { "player_tags_any": ["badge_a", "badge_b"], "deny_message": "Badge required" },
                      { "player_tags_all": ["quest"] }
                    ],
                    """ + SHOP_OFFERS);
            assertShopDenial(helper, player, shop, "VIP required");
            boolean rejected = false;
            try {
                CommandShopSessions.openShop(player, shop);
            } catch (IllegalStateException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected && !(player.containerMenu instanceof ShopMenu), "Denied shop must not open.");
            player.addTag("vip");
            assertShopDenial(helper, player, shop, "Badge required");
            player.addTag("badge_b");
            assertShopDenial(helper, player, shop, "Old fallback");
            player.addTag("quest");
            helper.assertTrue(ShopRegistry.evaluateAccess(helper.getLevel().getServer(), shop, player).allowed(),
                    "One matching ANY alternative and all other rules must allow access.");
            CommandShopSessions.openShop(player, shop);
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "Allowed shop must open.");

            ShopVisibilityData.get(helper.getLevel().getServer()).disable(SHOP_ID, "Administratively closed", "GameTest", System.currentTimeMillis());
            player.removeTag("vip");
            assertShopDenial(helper, player, shop, "Administratively closed");
        } finally {
            ShopVisibilityData.get(helper.getLevel().getServer()).enable(SHOP_ID);
            cleanup(helper, player);
        }
        helper.succeed();
    }

    @TestHolder(value = "global_and_local_bank_rules_refuse_opening", title = "Global and local bank rules refuse opening")
    @GameTest(batch = "access.bank_open", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void global_and_local_bank_rules_refuse_opening(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        Path globalBank = ShopRegistry.getGlobalBankFile();
        byte[] previousGlobal = Files.readAllBytes(globalBank);
        try {
            Files.writeString(globalBank, BANK_RULES);
            ShopDefinition shop = loadShop(helper, SHOP_OFFERS);
            CommandShopSessions.openShop(player, shop);
            ShopMenu originalMenu = (ShopMenu) player.containerMenu;
            UUID merchant = originalMenu.getCobbleMerchant().getMerchantUUID();
            helper.assertValueEqual("Bank VIP required", ShopRegistry.evaluateBankAccess(SHOP_ID, player).denialMessage().getString(),
                    "Global bank should report its first failed rule.");
            helper.assertTrue(CommandShopSessions.openCustomBank(player, merchant), "Denied bank packet must be consumed.");
            helper.assertTrue(player.containerMenu == originalMenu, "Bank denial must keep the existing shop open.");
            player.addTag("bank_vip");
            helper.assertValueEqual("Bank badges required", ShopRegistry.evaluateBankAccess(SHOP_ID, player).denialMessage().getString(),
                    "Global bank should advance to its second rule.");

            CommandShopGameTestSupport.writeLocalBankJson(SHOP_ID, """
                    { "conditions": [{ "player_tags_all": ["local"], "deny_message": "Local bank required" }],
                      "offers": [{ "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": 2 }] }
                    """);
            ShopRegistry.reload(helper.getLevel().registryAccess());
            helper.assertValueEqual("Local bank required", ShopRegistry.evaluateBankAccess(SHOP_ID, player).denialMessage().getString(),
                    "Local bank must replace global conditions.");
            player.addTag("local");
            helper.assertTrue(CommandShopSessions.openCustomBank(player, merchant), "Local bank open should be handled.");
            helper.assertTrue(player.containerMenu instanceof BankMenu, "Passing local rules must open bank despite failing global rules.");
        } finally {
            Files.write(globalBank, previousGlobal);
            cleanup(helper, player);
        }
        helper.succeed();
    }

    @TestHolder(value = "lost_bank_access_blocks_sale_and_returns_items", title = "Lost bank access blocks sale and returns items")
    @GameTest(batch = "access.bank_sell", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void lost_bank_access_blocks_sale_and_returns_items(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            openAllowedBank(helper, player);
            BankMenu bank = (BankMenu) player.containerMenu;
            bank.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));
            PlayerExtensionKt.setCobbleDollars(player, BigInteger.valueOf(50));
            player.removeTag("bank_badges");
            helper.assertTrue(CommandShopSessions.handleCustomSell(helper.getLevel().getServer(), player), "Revoked sale packet must be consumed.");
            assertClosedAndReturned(helper, player, bank);
            helper.assertTrue(BigInteger.valueOf(50).equals(PlayerExtensionKt.getCobbleDollars(player)), "Denied sale must not change money.");
        } finally {
            cleanup(helper, player);
        }
        helper.succeed();
    }

    @TestHolder(value = "lost_bank_access_closes_on_refresh", title = "Lost bank access closes on refresh")
    @GameTest(batch = "access.bank_refresh", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void lost_bank_access_closes_on_refresh(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            openAllowedBank(helper, player);
            BankMenu bank = (BankMenu) player.containerMenu;
            bank.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));
            player.removeTag("bank_vip");
            CommandShopSessions.refreshPlayerSession(player);
            assertClosedAndReturned(helper, player, bank);
            CommandShopSessions.refreshPlayerSession(player);
            helper.assertValueEqual(3, countEmeralds(player), "Repeated refresh must not duplicate returned items.");
        } finally {
            cleanup(helper, player);
        }
        helper.succeed();
    }

    @TestHolder(value = "visibility_rules_do_not_block_opening", title = "Visibility rules do not block opening")
    @GameTest(batch = "access.visibility", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void visibility_rules_do_not_block_opening(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        try {
            ShopDefinition shop = loadShop(helper, """
                    "categories": [{ "name": "Hidden",
                      "conditions": [{ "player_tags_all": ["hidden"], "deny_message": "Never an access denial" }],
                      "offers": [{ "id": "stone", "match": { "include": [{ "item": "minecraft:stone" }] }, "price": 1 }]
                    }]
                    """);
            CommandShopGameTestSupport.writeLocalBankJson(SHOP_ID, """
                    { "categories": [{ "name": "Hidden",
                      "conditions": [{ "player_tags_all": ["hidden"], "deny_message": "Never a bank denial" }],
                      "offers": [{ "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": 7 }]
                    }] }
                    """);
            ShopRegistry.reload(helper.getLevel().registryAccess());
            CommandShopSessions.openShop(player, shop);
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "Hidden categories must still allow opening shop.");
            UUID merchant = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
            helper.assertTrue(CommandShopSessions.openCustomBank(player, merchant), "Bank open should be handled.");
            helper.assertTrue(player.containerMenu instanceof BankMenu, "Hidden bank categories must still allow opening bank.");
            BankMenu bank = (BankMenu) player.containerMenu;
            helper.assertTrue(!bank.getBankRef().getBank().contains(ItemStack.EMPTY), "Filtered banks must not highlight empty slots.");
            var balanceBefore = fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt.getCobbleDollars(player);
            bank.getBankContainer().setItem(0, new ItemStack(Items.EMERALD, 3));
            CommandShopSessions.handleCustomSell(helper.getLevel().getServer(), player);
            helper.assertValueEqual(3, bank.getBankContainer().getItem(0).getCount(), "Empty bank must not consume deposits.");
            helper.assertValueEqual(balanceBefore, fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt.getCobbleDollars(player),
                    "Empty bank must not change the balance.");
            player.addTag("hidden");
            CommandShopSessions.refreshPlayerSession(player);
            helper.assertTrue(bank.getBankRef().getBank().contains(new ItemStack(Items.EMERALD)), "A newly visible bank offer must become sellable.");
            player.removeTag("hidden");
            CommandShopSessions.refreshPlayerSession(player);
            helper.assertTrue(!bank.getBankRef().getBank().contains(ItemStack.EMPTY)
                    && !bank.getBankRef().getBank().contains(new ItemStack(Items.EMERALD)), "Hiding the last offer must restore an empty bank.");
        } finally {
            cleanup(helper, player);
        }
        helper.succeed();
    }

    private static ShopDefinition loadShop(ExtendedGameTestHelper helper, String fields) throws Exception {
        CommandShopGameTestSupport.cleanupShop(SHOP_ID);
        CommandShopGameTestSupport.writeShopJson(SHOP_ID, "{" + fields + "}");
        ShopRegistry.reload(helper.getLevel().registryAccess());
        return ShopRegistry.getShop(SHOP_ID);
    }

    private static void assertShopDenial(ExtendedGameTestHelper helper, GameTestPlayer player, ShopDefinition shop, String expected) {
        ShopRegistry.ShopAccessResult access = ShopRegistry.evaluateAccess(helper.getLevel().getServer(), shop, player);
        helper.assertTrue(!access.allowed(), "Shop should be denied.");
        helper.assertValueEqual(expected, access.denialMessage().getString(), "Wrong first shop failure.");
    }

    private static void openAllowedBank(ExtendedGameTestHelper helper, GameTestPlayer player) throws Exception {
        ShopDefinition shop = loadShop(helper, SHOP_OFFERS);
        CommandShopGameTestSupport.writeLocalBankJson(SHOP_ID, BANK_RULES);
        ShopRegistry.reload(helper.getLevel().registryAccess());
        player.addTag("bank_vip");
        player.addTag("bank_badges");
        CommandShopSessions.openShop(player, shop);
        UUID merchant = ((ShopMenu) player.containerMenu).getCobbleMerchant().getMerchantUUID();
        CommandShopSessions.openCustomBank(player, merchant);
        helper.assertTrue(player.containerMenu instanceof BankMenu, "Bank fixture failed to open.");
    }

    private static void assertClosedAndReturned(ExtendedGameTestHelper helper, GameTestPlayer player, BankMenu bank) {
        helper.assertTrue(player.containerMenu == player.inventoryMenu, "Revoked bank must close.");
        helper.assertValueEqual(3, countEmeralds(player), "Bank must return every deposited item.");
        helper.assertTrue(bank.getBankContainer().isEmpty(), "Closed bank must retain no deposited items.");
    }

    private static int countEmeralds(GameTestPlayer player) {
        return player.getInventory().items.stream().filter(stack -> stack.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();
    }

    private static void cleanup(ExtendedGameTestHelper helper, GameTestPlayer player) throws Exception {
        player.closeContainer();
        CommandShopSessions.cleanupPlayer(player.getUUID());
        CommandShopGameTestSupport.cleanupShop(SHOP_ID);
        ShopRegistry.reload(helper.getLevel().registryAccess());
    }
}
