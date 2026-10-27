package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
class EconomyAuditLogServerTest {
    @AfterEach void cleanup() throws Exception {
        AuditLogService.shutdown();
        for (var file : AuditLogService.listReadableLogFiles()) Files.deleteIfExists(file);
        Files.deleteIfExists(AuditFiles.getConfigFile());
    }

    @Test void defaultsAddOnlyTransfersAndAdjustmentsToExistingConfigurations(MinecraftServer server) throws Exception {
        AuditConfig config = AuditFiles.parseConfig(JsonParser.parseString("""
                {"events":{"buy_success":false,"sell_success":true}}
                """).getAsJsonObject(), "old audit configuration");
        assertFalse(config.isEnabled(AuditEventType.BUY_SUCCESS));
        assertTrue(config.isEnabled(AuditEventType.SELL_SUCCESS));
        assertTrue(config.isEnabled(AuditEventType.PLAYER_TRANSFER));
        assertTrue(config.isEnabled(AuditEventType.BALANCE_ADJUSTED));
        assertFalse(config.isEnabled(AuditEventType.BATTLE_REWARD));
        assertFalse(config.isEnabled(AuditEventType.NATIVE_BUY_SUCCESS));
        assertFalse(config.isEnabled(AuditEventType.NATIVE_SELL_SUCCESS));
        assertEquals(AuditConfig.defaults(), AuditFiles.parseConfig(
                JsonParser.parseString(AuditFiles.defaultConfigText()).getAsJsonObject(), "defaults"));
    }

    @Test void transferIsOneExactRecordAndRejectsNonMovements(MinecraftServer server) throws Exception {
        AuditLogService.initialize();
        BigInteger amount = BigInteger.ONE.shiftLeft(90);
        AuditPlayerRef sender = player("Sender"), recipient = player("Recipient");
        UUID operation = UUID.randomUUID();
        AuditBalanceChange sent = new AuditBalanceChange(sender, amount.add(BigInteger.TEN), BigInteger.TEN);
        AuditBalanceChange received = new AuditBalanceChange(recipient, BigInteger.ONE, amount.add(BigInteger.ONE));
        AuditLogService.logPlayerTransfer(operation, sent, received);
        AuditLogService.logPlayerTransfer(UUID.randomUUID(), sent, new AuditBalanceChange(sender, BigInteger.ZERO, amount));
        AuditLogService.logPlayerTransfer(UUID.randomUUID(), sent, new AuditBalanceChange(recipient, BigInteger.ZERO, BigInteger.ONE));
        AuditLogService.logPlayerTransfer(UUID.randomUUID(), new AuditBalanceChange(sender, BigInteger.TEN, BigInteger.TEN), received);
        List<JsonObject> rows = rows();
        assertEquals(1, rows.size());
        JsonObject event = rows.getFirst();
        assertEquals(1, event.get("schema_version").getAsInt());
        assertEquals("player_transfer", event.get("type").getAsString());
        assertEquals(operation.toString(), event.get("operation_id").getAsString());
        assertEquals(sender.uuid().toString(), event.get("player_uuid").getAsString());
        assertEquals(recipient.uuid().toString(), event.get("recipient_uuid").getAsString());
        assertEquals(amount.toString(), event.get("currency_amount").getAsString());
        assertEquals(amount.negate().toString(), event.get("currency_delta").getAsString());
        assertEquals("transfer", event.get("currency_flow").getAsString());
        assertEquals(amount.add(BigInteger.TEN).toString(), event.get("balance_before").getAsString());
        assertEquals(amount.add(BigInteger.ONE).toString(), event.get("recipient_balance_after").getAsString());
        assertTrue(event.get("currency_amount").getAsJsonPrimitive().isString());
        assertFalse(event.has("shop_id"));
    }

    @Test void adjustmentsUseActualDeltaAndConsoleActor(MinecraftServer server) throws Exception {
        AuditLogService.initialize();
        AuditPlayerRef target = player("Target");
        AuditLogService.logBalanceAdjusted(UUID.randomUUID(), "Server", null, BalanceAdjustmentAction.REMOVE,
                BigInteger.valueOf(100), new AuditBalanceChange(target, BigInteger.TEN, BigInteger.ZERO));
        AuditLogService.logBalanceAdjusted(UUID.randomUUID(), "Admin", "admin-id", BalanceAdjustmentAction.SET,
                BigInteger.ZERO, new AuditBalanceChange(target, BigInteger.ZERO, BigInteger.ZERO));
        JsonObject event = rows().getFirst();
        assertEquals(1, rows().size());
        assertEquals("100", event.get("requested_amount").getAsString());
        assertEquals("10", event.get("currency_amount").getAsString());
        assertEquals("-10", event.get("currency_delta").getAsString());
        assertEquals("remove", event.get("action").getAsString());
        assertEquals("Server", event.get("actor_name").getAsString());
        assertTrue(event.get("actor_uuid").isJsonNull());
    }

    @Test void optionalSourcesRespectTheirSwitchesAndHaveNoCommandShopIdentity(MinecraftServer server) throws Exception {
        AuditLogService.initialize();
        AuditPlayerRef target = player("Target");
        AuditBalanceChange gain = new AuditBalanceChange(target, BigInteger.ZERO, BigInteger.TEN);
        AuditBalanceChange spend = new AuditBalanceChange(target, BigInteger.TEN, BigInteger.ZERO);
        List<ItemStack> items = List.of(new ItemStack(Items.APPLE, 3));
        AuditLogService.logBattleReward(UUID.randomUUID(), gain);
        AuditLogService.logNativeBuySuccess(UUID.randomUUID(), spend, items, null);
        AuditLogService.logNativeSellSuccess(UUID.randomUUID(), gain, items, "merchant-id");
        assertTrue(rows().isEmpty());
        Files.writeString(AuditFiles.getConfigFile(), """
                {"events":{"battle_reward":true,"native_buy_success":true,"native_sell_success":true}}
                """);
        AuditLogService.reload();
        AuditLogService.logBattleReward(UUID.randomUUID(), gain);
        AuditLogService.logNativeBuySuccess(UUID.randomUUID(), spend, items, null);
        AuditLogService.logNativeSellSuccess(UUID.randomUUID(), gain, items, "merchant-id");
        List<JsonObject> events = rows();
        assertEquals(3, events.size());
        for (JsonObject event : events) {
            assertFalse(event.has("shop_id"));
            assertEquals("10", event.get("currency_amount").getAsString());
        }
        assertEquals("-10", events.get(1).get("currency_delta").getAsString());
        JsonObject line = events.get(1).getAsJsonArray("item_lines").get(0).getAsJsonObject();
        assertEquals("minecraft:apple", line.get("item").getAsString());
        assertEquals(3, line.get("count").getAsInt());
        assertFalse(line.has("unit_price"));
        assertEquals("merchant-id", events.get(2).get("merchant_uuid").getAsString());
        Files.writeString(AuditFiles.getConfigFile(), "{\"enabled\":false}");
        AuditLogService.reload();
        AuditLogService.logPlayerTransfer(UUID.randomUUID(), spend, gain);
        AuditLogService.logBalanceAdjusted(UUID.randomUUID(), "Server", null, BalanceAdjustmentAction.GIVE, BigInteger.TEN, gain);
        AuditLogService.logBattleReward(UUID.randomUUID(), gain);
        assertEquals(3, rows().size());
    }

    private static AuditPlayerRef player(String name) { return new AuditPlayerRef(UUID.randomUUID(), name); }
    private static List<JsonObject> rows() throws Exception {
        AuditLogService.prepareRead();
        if (!Files.exists(AuditLogService.getLogFile())) return List.of();
        return Files.readAllLines(AuditLogService.getLogFile()).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
