package fr.cobbledollars.commandshops.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.admin.config.AdminConfigService;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ForEachTest(idPrefix = "admin_config_history.", groups = "admin_config_history", side = Dist.DEDICATED_SERVER)
public final class CommandShopAdminConfigHistoryGameTests {
    private static final String SHOP_ID = "gametest_admin_config_history";
    private static final String SHOP_FILE = "shops/" + SHOP_ID + "/shop.json";
    private static final String PRICE_PATH = "/categories/0/offers/0/price";

    private CommandShopAdminConfigHistoryGameTests() { }

    @TestHolder(value = "save_records_actor_and_recording_policy_changes", title = "Real administrator saves record exact changes and their own recording-policy transitions")
    @GameTest(batch = "admin_config_history.integration", timeoutTicks = 400, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void save_records_actor_and_recording_policy_changes(ExtendedGameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        GameTestPlayer admin = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        server.getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));
        Map<Path, byte[]> globalFiles = new LinkedHashMap<>();
        boolean fixtureOwned = false;
        try {
            for (String file : List.of("audit.json", "commands.json", "feedback.json", "global_bank.json")) {
                Path path = ShopFiles.getConfigDirectory().resolve(file);
                globalFiles.put(path, Files.readAllBytes(path));
            }
            helper.assertTrue(!Files.exists(ShopRegistry.getShopDirectory().resolve(SHOP_ID)), "The fixture shop ID must be unused.");
            fixtureOwned = true;
            JsonObject audit = JsonParser.parseString(Files.readString(AuditFiles.getConfigFile())).getAsJsonObject();
            audit.addProperty("enabled", true);
            JsonObject events = audit.has("events") ? audit.getAsJsonObject("events") : new JsonObject();
            audit.add("events", events);
            events.addProperty("config_changed", true);
            Files.writeString(AuditFiles.getConfigFile(), audit.toString());
            CommandShopGameTestSupport.writeShopJson(SHOP_ID, """
                    { "categories": [{ "name": "Configuration history", "offers": [
                      { "id": "tracked_price", "match": { "include": [{ "item": "minecraft:emerald" }] }, "price": 25 }
                    ] }] }
                    """);
            ShopRegistry.reload(server.registryAccess());
            ShopFeedbackService.reload();
            AuditLogService.reload();
            AdminConfigService.rememberAppliedConfiguration();

            int initialCount = history(admin).size();
            JsonObject draft = snapshot(server);
            price(draft, 40);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), initialCount, SHOP_FILE, PRICE_PATH, "25", "40");
            helper.assertValueEqual(BigInteger.valueOf(40), ShopRegistry.getShop(SHOP_ID).getOfferById("tracked_price").price(), "The audited price must be the active server price.");

            int count = history(admin).size();
            save(helper, server, admin, snapshot(server));
            helper.assertValueEqual(count, history(admin).size(), "Saving an unchanged configuration must not create history.");

            draft = snapshot(server);
            audit(draft).getAsJsonObject("events").addProperty("config_changed", false);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), count, "audit.json", "/events/config_changed", "true", "false");
            helper.assertTrue(!AuditLogService.prepareRead().enabledEvents().contains("config_changed"), "The individual recording option must be disabled after its own change was recorded.");
            count = history(admin).size();
            draft = snapshot(server);
            price(draft, 41);
            save(helper, server, admin, draft);
            helper.assertValueEqual(count, history(admin).size(), "Subsequent edits must honor the disabled configuration-history option.");

            draft = snapshot(server);
            audit(draft).getAsJsonObject("events").addProperty("config_changed", true);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), count, "audit.json", "/events/config_changed", "false", "true");
            count = history(admin).size();
            draft = snapshot(server);
            price(draft, 42);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), count, SHOP_FILE, PRICE_PATH, "41", "42");

            count = history(admin).size();
            draft = snapshot(server);
            audit(draft).addProperty("enabled", false);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), count, "audit.json", "/enabled", "true", "false");
            helper.assertTrue(!AuditLogService.prepareRead().enabled(), "The global audit setting must take effect after its own change was recorded.");
            count = history(admin).size();
            draft = snapshot(server);
            price(draft, 43);
            save(helper, server, admin, draft);
            helper.assertValueEqual(count, history(admin).size(), "Globally disabled audit must suppress subsequent configuration-history entries.");

            draft = snapshot(server);
            audit(draft).addProperty("enabled", true);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), count, "audit.json", "/enabled", "false", "true");
            count = history(admin).size();
            draft = snapshot(server);
            price(draft, 44);
            save(helper, server, admin, draft);
            assertAppliedChange(helper, history(admin), count, SHOP_FILE, PRICE_PATH, "43", "44");
            helper.assertValueEqual(BigInteger.valueOf(44), ShopRegistry.getShop(SHOP_ID).getOfferById("tracked_price").price(), "Edits while recording was disabled must still have been applied.");
            helper.assertTrue(history(admin).stream().skip(initialCount).allMatch(row -> row.get("actor_uuid").getAsString().equals(admin.getStringUUID())
                            && row.get("actor_name").getAsString().equals(admin.getGameProfile().getName())
                            && row.get("source").getAsString().equals("admin")),
                    "Prepared and terminal records must identify the real administrator without attributing them to the console.");
        } finally {
            CommandShopSessions.cleanupPlayer(admin.getUUID());
            server.getPlayerList().deop(admin.getGameProfile());
            for (var file : globalFiles.entrySet()) Files.write(file.getKey(), file.getValue());
            if (fixtureOwned) CommandShopGameTestSupport.cleanupShop(SHOP_ID);
            AdminConfigService.clearAppliedConfiguration();
            ShopRegistry.reload(server.registryAccess());
            ShopFeedbackService.reload();
            AuditLogService.reload();
            AdminConfigService.rememberAppliedConfiguration();
        }
        helper.succeed();
    }

    private static JsonObject snapshot(MinecraftServer server) throws Exception {
        return JsonParser.parseString(AdminConfigService.snapshot(server)).getAsJsonObject();
    }

    private static JsonObject audit(JsonObject draft) {
        return draft.getAsJsonObject("files").getAsJsonObject("audit.json");
    }

    private static void price(JsonObject draft, int value) {
        draft.getAsJsonObject("files").getAsJsonObject(SHOP_FILE).getAsJsonArray("categories").get(0).getAsJsonObject()
                .getAsJsonArray("offers").get(0).getAsJsonObject().addProperty("price", value);
    }

    private static void save(ExtendedGameTestHelper helper, MinecraftServer server, GameTestPlayer admin, JsonObject draft) {
        AdminConfigService.SaveResult saved = AdminConfigService.save(server, draft.toString(), admin);
        helper.assertTrue(saved.success(), "The real administrator save failed: " + saved.message().english());
    }

    private static List<JsonObject> history(GameTestPlayer admin) throws Exception {
        AuditLogService.prepareRead();
        if (!Files.exists(AuditLogService.getLogFile())) return List.of();
        try (var lines = Files.lines(AuditLogService.getLogFile())) {
            return lines.map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(row -> row.get("type").getAsString().equals("config_changed") && row.has("actor_uuid")
                            && row.get("actor_uuid").getAsString().equals(admin.getStringUUID())).toList();
        }
    }

    private static void assertAppliedChange(ExtendedGameTestHelper helper, List<JsonObject> history, int previousCount,
                                           String file, String path, String beforeJson, String afterJson) {
        List<JsonObject> added = history.subList(previousCount, history.size());
        helper.assertValueEqual(2, added.size(), "One modified field must create its preparation and one applied outcome.");
        JsonObject change = added.getFirst();
        JsonObject outcome = added.getLast();
        helper.assertValueEqual("prepared", change.get("phase").getAsString(), "The exact proposed change must precede its activation result.");
        helper.assertValueEqual(file, change.get("file").getAsString(), "The modified file must be identified.");
        helper.assertValueEqual(path, change.get("path").getAsString(), "The exact JSON pointer must be identified.");
        helper.assertTrue(change.get("before_present").getAsBoolean() && change.get("after_present").getAsBoolean(), "Both values must be explicitly present for a field edit.");
        helper.assertValueEqual(beforeJson, change.get("before").toString(), "The previous value must be exact.");
        helper.assertValueEqual(afterJson, change.get("after").toString(), "The submitted value must be exact.");
        helper.assertValueEqual("applied", outcome.get("phase").getAsString(), "A successful save must have an applied outcome even when it disables recording.");
        helper.assertValueEqual(change.get("operation_id").getAsString(), outcome.get("operation_id").getAsString(), "The before/after record and activation result must share one operation ID.");
        helper.assertValueEqual(1, outcome.get("change_count").getAsInt(), "The outcome must count only the edited field.");
    }
}
