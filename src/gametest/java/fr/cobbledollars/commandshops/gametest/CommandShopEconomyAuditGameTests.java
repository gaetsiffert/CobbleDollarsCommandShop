package fr.cobbledollars.commandshops.gametest;

import com.cobblemon.mod.common.api.Priority;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.harmex.cobbledollars.common.CobbleDollars;
import fr.harmex.cobbledollars.common.api.bank.Bank;
import fr.harmex.cobbledollars.common.api.event.CobbleDollarsEarnedEvent;
import fr.harmex.cobbledollars.common.api.event.CobbleDollarsEvents;
import fr.harmex.cobbledollars.common.api.shop.Offer;
import fr.harmex.cobbledollars.common.command.CobbleDollarsCommand;
import fr.harmex.cobbledollars.common.network.handlers.server.BuyHandler;
import fr.harmex.cobbledollars.common.network.handlers.server.SellHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.BuyPacket;
import fr.harmex.cobbledollars.common.network.packets.c2s.SellPacket;
import fr.harmex.cobbledollars.common.utils.MiscUtilsKt;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;

@ForEachTest(idPrefix = "economy_audit.", groups = "economy_audit", side = Dist.DEDICATED_SERVER)
public final class CommandShopEconomyAuditGameTests {
    private CommandShopEconomyAuditGameTests() { }

    @TestHolder(value = "native_actions_record_actual_money_once", title = "Native economy hooks record actual committed money without duplicate shop entries")
    @GameTest(batch = "economy_audit.native", timeoutTicks = 160, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void native_actions_record_actual_money_once(ExtendedGameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var sender = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        var recipient = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        byte[] originalConfig = Files.readAllBytes(AuditFiles.getConfigFile());
        double multiplier = CobbleDollars.INSTANCE.getConfig().getCobbleDollarsIncomeMultiplier();
        try {
            Files.writeString(AuditFiles.getConfigFile(), """
                    {"events":{"player_transfer":true,"balance_adjusted":true,"battle_reward":true,
                    "native_buy_success":true,"native_sell_success":true}}
                    """);
            AuditLogService.reload();
            BigInteger huge = BigInteger.ONE.shiftLeft(80);
            PlayerExtensionKt.setCobbleDollars(sender, huge.add(BigInteger.TEN));
            PlayerExtensionKt.setCobbleDollars(recipient, BigInteger.valueOf(7));
            int payResult = command("pay", sender.createCommandSourceStack(), recipient, huge);
            helper.assertValueEqual(0, payResult, "The upstream int result should overflow to zero for this successful payment.");
            List<JsonObject> transfers = rows(sender, "player_transfer");
            helper.assertValueEqual(1, transfers.size(), "A payment must create one event for both participants.");
            helper.assertValueEqual(huge.toString(), transfers.getFirst().get("currency_amount").getAsString(), "Payment amount lost precision.");
            helper.assertValueEqual(recipient.getUUID().toString(), transfers.getFirst().get("recipient_uuid").getAsString(), "Recipient is missing.");
            command("pay", sender.createCommandSourceStack(), recipient, huge);
            command("pay", sender.createCommandSourceStack(), sender, BigInteger.ONE);
            helper.assertValueEqual(1, rows(sender, "player_transfer").size(), "Failed and self payments must not add movements.");

            command("remove", server.createCommandSourceStack(), List.of(sender), BigInteger.valueOf(100));
            JsonObject removed = rows(sender, "balance_adjusted").getFirst();
            helper.assertValueEqual("10", removed.get("currency_amount").getAsString(), "Remove must record the clamped actual amount.");
            helper.assertValueEqual("100", removed.get("requested_amount").getAsString(), "Requested amount should remain available.");
            helper.assertTrue(removed.get("actor_uuid").isJsonNull(), "A console actor must not be assigned a player UUID.");
            command("set", server.createCommandSourceStack(), List.of(sender), BigInteger.ZERO);
            helper.assertValueEqual(1, rows(sender, "balance_adjusted").size(), "Setting an unchanged balance is not a movement.");
            command("give", server.createCommandSourceStack(), List.of(sender, recipient), BigInteger.valueOf(4));
            JsonObject senderGive = rows(sender, "balance_adjusted").getLast();
            JsonObject recipientGive = rows(recipient, "balance_adjusted").getLast();
            helper.assertValueEqual(senderGive.get("operation_id").getAsString(), recipientGive.get("operation_id").getAsString(),
                    "Multitarget changes must share an operation ID.");

            AtomicReference<BigInteger> rawReward = new AtomicReference<>();
            var observed = CobbleDollarsEvents.COBBLE_DOLLARS_EARNED.subscribe(Priority.NORMAL,
                    (Consumer<CobbleDollarsEarnedEvent>) event -> { if (event.getPlayer() == sender) rawReward.set(event.getAmount()); });
            try {
                CobbleDollars.INSTANCE.getConfig().setCobbleDollarsIncomeMultiplier(2.5);
                MiscUtilsKt.calculateAndAwardCobbleDollars(sender, List.of(50));
            } finally { observed.unsubscribe(); }
            JsonObject reward = rows(sender, "battle_reward").getFirst();
            BigInteger actualReward = new BigDecimal(rawReward.get()).multiply(BigDecimal.valueOf(2.5)).toBigInteger();
            helper.assertValueEqual(actualReward.toString(), reward.get("currency_amount").getAsString(), "Reward must include the actual multiplier.");
            var canceled = CobbleDollarsEvents.COBBLE_DOLLARS_EARNED.subscribe(Priority.HIGHEST,
                    (Consumer<CobbleDollarsEarnedEvent>) event -> { if (event.getPlayer() == sender) event.cancel(); });
            try { MiscUtilsKt.calculateAndAwardCobbleDollars(sender, List.of(50)); }
            finally { canceled.unsubscribe(); }
            helper.assertValueEqual(1, rows(sender, "battle_reward").size(), "Canceled rewards must not be logged as income.");
            int eventsBeforeApi = rows(sender, "").size();
            PlayerExtensionKt.setCobbleDollars(sender, BigInteger.valueOf(100));
            PlayerExtensionKt.earnCobbleDollars(sender, BigInteger.ONE, false);
            helper.assertValueEqual(eventsBeforeApi, rows(sender, "").size(), "Generic thirdparty balance APIs are outside the configured scope.");

            sender.containerMenu = new ShopMenu(1, sender.getInventory());
            Method nativeBuy = BuyHandler.class.getDeclaredMethod("buy", ServerPlayer.class, ItemStack.class, BigInteger.class);
            nativeBuy.setAccessible(true);
            nativeBuy.invoke(BuyHandler.INSTANCE, sender, new ItemStack(Items.APPLE, 3), BigInteger.valueOf(6));
            helper.assertValueEqual(1, rows(sender, "native_buy_success").size(), "Native buy helper must record its actual debit.");
            helper.assertValueEqual(3, rows(sender, "native_buy_success").getFirst().getAsJsonArray("item_lines")
                    .get(0).getAsJsonObject().get("count").getAsInt(), "Inventory insertion must not consume the audit item count.");
            var bank = new Bank(Map.of(new Offer(new ItemStack(Items.APPLE), BigInteger.valueOf(2), -1), List.of()));
            var bankMenu = new BankMenu(2, sender.getInventory());
            sender.containerMenu = bankMenu;
            bankMenu.getBankContainer().setItem(0, new ItemStack(Items.APPLE, 3));
            SellHandler.INSTANCE.handle(new SellPacket(new CobbleDollarsShopHolder.BankRef.Inline(bank), BigInteger.valueOf(6), UUID.randomUUID(), false), server, sender);
            helper.assertValueEqual(1, rows(sender, "native_sell_success").size(), "Native bank sale must record its actual credit.");
            helper.assertTrue(bankMenu.getBankContainer().getItem(0).isEmpty(), "The native sale should still clear the sold stack.");
            SellHandler.INSTANCE.handle(new SellPacket(new CobbleDollarsShopHolder.BankRef.Inline(bank), BigInteger.ZERO, UUID.randomUUID(), false), server, sender);
            helper.assertValueEqual(1, rows(sender, "native_sell_success").size(), "An empty native sale must not create a movement.");

            CommandShopSessions.openShop(sender, ShopRegistry.getShop("general_store"));
            ShopMenu custom = (ShopMenu) sender.containerMenu;
            Offer customOffer = custom.getShop().get(0).getOffers().getFirst();
            BuyHandler.INSTANCE.handle(new BuyPacket(customOffer, 0, 0, 0, 1, false,
                    custom.getCobbleMerchant().getMerchantUUID()), server, sender);
            helper.assertValueEqual(1, rows(sender, "buy_success").size(), "Custom purchases should retain their existing audit entry.");
            helper.assertValueEqual(1, rows(sender, "native_buy_success").size(), "Custom purchases must not also count as native purchases.");
            UUID merchant = custom.getCobbleMerchant().getMerchantUUID();
            helper.assertTrue(CommandShopSessions.openCustomBank(sender, merchant), "Expected the custom bank to open.");
            ((BankMenu) sender.containerMenu).getBankContainer().setItem(0, new ItemStack(Items.COAL, 2));
            SellHandler.INSTANCE.handle(new SellPacket(new CobbleDollarsShopHolder.BankRef.Inline(bank), BigInteger.ZERO, merchant, false), server, sender);
            helper.assertValueEqual(1, rows(sender, "sell_success").size(), "Custom sales should retain their existing audit entry.");
            helper.assertValueEqual(1, rows(sender, "native_sell_success").size(), "Custom sales must not also count as native sales.");
            for (JsonObject event : rows(sender, "")) {
                if (List.of("player_transfer", "balance_adjusted", "battle_reward", "native_buy_success", "native_sell_success")
                        .contains(event.get("type").getAsString())) helper.assertTrue(!event.has("shop_id"), "Native events must not invent a command shop.");
            }
        } finally {
            CobbleDollars.INSTANCE.getConfig().setCobbleDollarsIncomeMultiplier(multiplier);
            CommandShopSessions.cleanupPlayer(sender.getUUID());
            sender.closeContainer();
            recipient.closeContainer();
            Files.write(AuditFiles.getConfigFile(), originalConfig);
            AuditLogService.reload();
        }
        helper.succeed();
    }

    private static int command(String action, CommandSourceStack source, Object targets, BigInteger amount) throws Exception {
        Class<?> targetType = action.equals("pay") ? ServerPlayer.class : Collection.class;
        Method method = CobbleDollarsCommand.class.getDeclaredMethod(action, CommandSourceStack.class, targetType, BigInteger.class);
        method.setAccessible(true);
        return (int) method.invoke(CobbleDollarsCommand.INSTANCE, source, targets, amount);
    }

    private static List<JsonObject> rows(ServerPlayer player, String type) throws Exception {
        AuditLogService.prepareRead();
        if (!Files.exists(AuditLogService.getLogFile())) return List.of();
        return Files.readAllLines(AuditLogService.getLogFile()).stream().map(line -> JsonParser.parseString(line).getAsJsonObject())
                .filter(event -> event.has("player_uuid") && player.getUUID().toString().equals(event.get("player_uuid").getAsString()))
                .filter(event -> type.isEmpty() || type.equals(event.get("type").getAsString())).toList();
    }
}
