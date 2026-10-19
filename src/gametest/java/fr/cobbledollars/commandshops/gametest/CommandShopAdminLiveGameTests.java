package fr.cobbledollars.commandshops.gametest;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.admin.runtime.AdminLiveService;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "admin_live.", groups = "admin_live", side = Dist.DEDICATED_SERVER)
public final class CommandShopAdminLiveGameTests {
    private CommandShopAdminLiveGameTests() {
    }

    @TestHolder(value = "permission_is_enforced_in_service", title = "Live administration always requires operator permission")
    @GameTest(batch = "admin_live.permission", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void permission_is_enforced_in_service(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        var server = helper.getLevel().getServer();
        server.getPlayerList().deop(player.getGameProfile());
        ShopVisibilityData visibility = ShopVisibilityData.get(server);
        CompoundTag before = visibility.save(new CompoundTag(), helper.getLevel().registryAccess());
        for (String action : new String[] { "overview", "stock", "visibility", "set_stock", "restock_shop" }) {
            boolean rejected = false;
            try {
                request(player, action, "general_store", "", "", "", 0, false, "Untrusted", false);
            } catch (SecurityException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "Unprivileged action must be rejected: " + action);
        }
        helper.assertValueEqual(before, visibility.save(new CompoundTag(), helper.getLevel().registryAccess()),
                "A rejected request must not change visibility.");
        helper.succeed();
    }

    @TestHolder(value = "visibility_closes_shop_and_records_author", title = "Live visibility persists and closes the active shop")
    @GameTest(batch = "admin_live.visibility", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void visibility_closes_shop_and_records_author(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer admin = admin(helper);
        GameTestPlayer viewer = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        String shopId = "gametest_admin_live_visibility";
        try {
            ShopDefinition shop = setupShop(helper, shopId);
            CommandShopSessions.openShop(viewer, shop);
            helper.assertTrue(viewer.containerMenu instanceof ShopMenu, "Viewer should start inside the shop.");

            JsonObject response = request(admin, "visibility", shopId, "", "", "", 0, false, "Live maintenance", false);

            var status = ShopVisibilityData.get(helper.getLevel().getServer()).status(shopId);
            helper.assertTrue(!status.enabled(), "Visibility must be disabled.");
            helper.assertValueEqual("Live maintenance", status.message(), "The customized denial must be stored.");
            helper.assertValueEqual(admin.getGameProfile().getName(), status.changedBy(), "The acting administrator must be recorded.");
            helper.assertTrue(status.changedAtMillis() > 0, "The change time must be recorded.");
            var visibilityFile = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/cobbledollarscommandshops_shop_visibility.dat");
            CompoundTag persisted = NbtIo.readCompressed(visibilityFile, NbtAccounter.unlimitedHeap());
            helper.assertValueEqual("Live maintenance", persisted.getCompound("data").getCompound("shops").getCompound(shopId).getString("message"),
                    "The visibility change must reach disk before the response is returned.");
            helper.assertTrue(!(viewer.containerMenu instanceof ShopMenu), "Disabling must immediately close the active shop.");
            helper.assertTrue(response.getAsJsonArray("shops").asList().stream().anyMatch(element ->
                            element.getAsJsonObject().get("id").getAsString().equals(shopId)
                                    && !element.getAsJsonObject().get("enabled").getAsBoolean()),
                    "The response must include authoritative disabled visibility.");
            request(admin, "visibility", shopId, "", "", "", 0, true, "", false);
            helper.assertTrue(ShopVisibilityData.get(helper.getLevel().getServer()).status(shopId).enabled(),
                    "Enabling must remove the persisted disabled override.");
        } finally {
            CommandShopSessions.cleanupPlayer(viewer.getUUID());
            cleanup(helper, admin, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "offline_stock_edits_preserve_other_variants", title = "Offline stock editing preserves concrete item identities")
    @GameTest(batch = "admin_live.stock", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void offline_stock_edits_preserve_other_variants(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer admin = admin(helper);
        String shopId = "gametest_admin_live_variants";
        UUID offline = UUID.fromString("3f436dda-3af5-49c4-8c5a-b67a220486e4");
        try {
            ShopDefinition shop = setupShop(helper, shopId);
            var variants = shop.getOfferById("gems").createResolvedOffers();
            ResolvedShopOffer first = variants.getFirst();
            ResolvedShopOffer second = variants.getLast();

            JsonObject changed = request(admin, "set_stock", shopId, offline.toString(), "gems", first.stockKey(), 1, false, "", false);

            PlayerShopStockData data = PlayerShopStockData.get(helper.getLevel().getServer());
            helper.assertValueEqual(1, data.resolveStock(offline, shop, first, System.currentTimeMillis()), "Selected variant must be changed.");
            helper.assertValueEqual(5, data.resolveStock(offline, shop, second, System.currentTimeMillis()), "Sibling variant must remain full.");
            helper.assertValueEqual(5, data.resolveStock(admin.getUUID(), shop, first, System.currentTimeMillis()), "Another player's stock must remain full.");
            helper.assertValueEqual(2, changed.getAsJsonArray("offers").size(), "Finite variants must have separate rows; unlimited offers are excluded.");
            helper.assertValueEqual(offline.toString(), changed.get("playerId").getAsString(), "The selected offline UUID must be preserved.");
            JsonObject overview = request(admin, "overview", "", "", "", "", 0, false, "", false);
            helper.assertTrue(overview.getAsJsonArray("players").asList().stream().anyMatch(element ->
                            element.getAsJsonObject().get("id").getAsString().equals(offline.toString())
                                    && !element.getAsJsonObject().get("online").getAsBoolean()),
                    "Known offline stock owners must be selectable.");

            request(admin, "set_stock", shopId, offline.toString(), "gems", second.stockKey(), 0, false, "", false);
            request(admin, "restock_offer", shopId, offline.toString(), "gems", first.stockKey(), 0, false, "", false);
            helper.assertValueEqual(5, data.resolveStock(offline, shop, first, System.currentTimeMillis()), "Refill must restore its selected variant.");
            helper.assertValueEqual(0, data.resolveStock(offline, shop, second, System.currentTimeMillis()), "Refilling one variant must preserve its sibling.");
            request(admin, "restock_shop", shopId, offline.toString(), "", "", 0, false, "", false);
            helper.assertValueEqual(5, data.resolveStock(offline, shop, second, System.currentTimeMillis()), "Shop refill must restore every finite variant.");
        } finally {
            cleanup(helper, admin, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "invalid_bulk_edits_do_not_mutate_stock", title = "Invalid bulk stock changes are rejected before any mutation")
    @GameTest(batch = "admin_live.invalid_stock", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void invalid_bulk_edits_do_not_mutate_stock(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer admin = admin(helper);
        GameTestPlayer other = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        String shopId = "gametest_admin_live_invalid";
        try {
            ShopDefinition shop = setupShop(helper, shopId);
            ResolvedShopOffer offer = shop.getOfferById("gems").createResolvedOffers().getFirst();
            PlayerShopStockData data = PlayerShopStockData.get(helper.getLevel().getServer());
            data.setRemainingStock(admin.getUUID(), shop, offer, 1, System.currentTimeMillis());
            data.setRemainingStock(other.getUUID(), shop, offer, 2, System.currentTimeMillis());
            CompoundTag before = data.save(new CompoundTag(), helper.getLevel().registryAccess());
            assertRejected(helper, () -> request(admin, "set_stock", shopId, "", "gems", offer.stockKey(), 6, false, "", true));
            assertRejected(helper, () -> request(admin, "set_stock", shopId, "", "gems", "stale_variant", 0, false, "", true));
            assertRejected(helper, () -> request(admin, "set_stock", shopId, "", "gems", "", 0, false, "", true));
            assertRejected(helper, () -> request(admin, "set_stock", shopId, "", "unlimited", "unlimited", 0, false, "", true));
            helper.assertValueEqual(before, data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                    "Rejected bulk edits must preserve all player stock data.");

            JsonObject result = request(admin, "set_stock", shopId, admin.getStringUUID(), "gems", offer.stockKey(), 3, false, "", true);
            helper.assertTrue(result.get("affectedPlayers").getAsInt() >= 2, "Online bulk edit must include both connected players.");
            helper.assertValueEqual(3, data.resolveStock(admin.getUUID(), shop, offer, System.currentTimeMillis()), "Bulk edit must apply to admin.");
            helper.assertValueEqual(3, data.resolveStock(other.getUUID(), shop, offer, System.currentTimeMillis()), "Bulk edit must apply to the other online player.");
        } finally {
            cleanup(helper, admin, shopId);
        }
        helper.succeed();
    }

    @TestHolder(value = "manual_stock_history_records_only_effective_admin_edits", title = "Manual stock history identifies administrators, owners and concrete variants")
    @GameTest(batch = "admin_live.stock_history", timeoutTicks = 200, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void manual_stock_history_records_only_effective_admin_edits(ExtendedGameTestHelper helper) throws Exception {
        GameTestPlayer admin = admin(helper);
        GameTestPlayer other = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        var server = helper.getLevel().getServer();
        String shopId = "gametest_admin_live_stock_history";
        UUID offline = UUID.randomUUID();
        AuditFiles.ensureDefaultConfigExists();
        String originalAuditConfig = Files.readString(AuditFiles.getConfigFile());
        try {
            JsonObject config = JsonParser.parseString(originalAuditConfig).getAsJsonObject();
            config.addProperty("enabled", true);
            JsonObject events = config.has("events") ? config.getAsJsonObject("events") : new JsonObject();
            config.add("events", events);
            events.addProperty("stock_changed", true);
            Files.writeString(AuditFiles.getConfigFile(), config.toString());
            AuditLogService.reload();
            CommandShopGameTestSupport.writeShopJson(shopId, """
                    { "categories": [{ "name": "History", "offers": [
                      { "id": "gems", "match": { "include": [
                        { "item": "minecraft:emerald" }, { "item": "minecraft:diamond" }
                      ] }, "price": 5, "stock": 5,
                        "restock": { "type": "interval", "amount": 2, "every_seconds": 60 } }
                    ] }] }
                    """);
            ShopRegistry.reload(helper.getLevel().registryAccess());
            ShopDefinition shop = ShopRegistry.getShop(shopId);
            var variants = shop.getOfferById("gems").createResolvedOffers();
            ResolvedShopOffer first = variants.getFirst();
            ResolvedShopOffer second = variants.getLast();
            PlayerShopStockData data = PlayerShopStockData.get(server);

            UUID edit = UUID.randomUUID();
            AdminLiveService.execute(admin, edit, "set_stock", shopId, offline.toString(), "gems", first.stockKey(), 1, false, "", false);
            List<JsonObject> logged = stockHistory(shopId);
            helper.assertValueEqual(1, logged.size(), "One concrete manual edit must create one event.");
            JsonObject entry = logged.getFirst();
            helper.assertValueEqual(edit.toString(), entry.get("operation_id").getAsString(), "The request identity must be retained.");
            helper.assertValueEqual("set_stock", entry.get("action").getAsString(), "The manual action must be named.");
            helper.assertValueEqual(admin.getStringUUID(), entry.get("actor_uuid").getAsString(), "The administrator UUID must be retained.");
            helper.assertValueEqual(admin.getGameProfile().getName(), entry.get("actor_name").getAsString(), "The administrator name must be retained.");
            helper.assertValueEqual(offline.toString(), entry.get("player_uuid").getAsString(), "The affected offline owner must be distinct from the actor.");
            helper.assertValueEqual("gems", entry.get("offer_id").getAsString(), "The source offer must be retained.");
            helper.assertValueEqual(first.stockKey(), entry.get("stock_key").getAsString(), "The exact concrete variant must be retained.");
            helper.assertTrue(entry.get("item_id").getAsString().startsWith("minecraft:"), "The concrete item ID must be retained.");
            helper.assertValueEqual(5, entry.get("stock_before").getAsInt(), "Before must be the remaining bundle count.");
            helper.assertValueEqual(1, entry.get("stock_after").getAsInt(), "After must be the persisted remaining bundle count.");
            var stockFile = server.getWorldPath(LevelResource.ROOT).resolve("data/cobbledollarscommandshops_player_stock.dat");
            CompoundTag persisted = NbtIo.readCompressed(stockFile, NbtAccounter.unlimitedHeap());
            helper.assertValueEqual(1, persisted.getCompound("data").getCompound("players").getCompound(offline.toString())
                    .getCompound(shopId).getCompound(first.stockKey()).getInt("stock"), "The stock record must be on disk when its event is published.");

            request(admin, "stock", shopId, offline.toString(), "", "", 0, false, "", false);
            request(admin, "overview", "", "", "", "", 0, false, "", false);
            request(admin, "set_stock", shopId, offline.toString(), "gems", first.stockKey(), 1, false, "", false);
            request(admin, "restock_offer", shopId, offline.toString(), "gems", second.stockKey(), 0, false, "", false);
            assertRejected(helper, () -> request(admin, "set_stock", shopId, "", "gems", first.stockKey(), 6, false, "", true));
            boolean denied = false;
            try {
                request(other, "set_stock", shopId, offline.toString(), "gems", first.stockKey(), 0, false, "", false);
            } catch (SecurityException expected) {
                denied = true;
            }
            helper.assertTrue(denied, "An unauthorized manual edit must be rejected.");
            data.consumeStock(offline, shop, first, 1, System.currentTimeMillis());
            helper.assertValueEqual(1, stockHistory(shopId).size(), "Reads, no-ops, rejections and ordinary stock consumption must not create manual history.");

            request(admin, "restock_offer", shopId, offline.toString(), "gems", "", 0, false, "", false);
            helper.assertValueEqual(2, stockHistory(shopId).size(), "Refilling all variants must record only the depleted variant.");
            helper.assertValueEqual("restock_offer", stockHistory(shopId).getLast().get("action").getAsString(), "An offer refill must be identified.");
            data.setRemainingStock(offline, shop, first, 2, System.currentTimeMillis());
            data.setRemainingStock(offline, shop, second, 3, System.currentTimeMillis());
            UUID refill = UUID.randomUUID();
            AdminLiveService.execute(admin, refill, "restock_shop", shopId, offline.toString(), "", "", 0, false, "", false);
            List<JsonObject> refillEntries = stockHistory(shopId).stream()
                    .filter(row -> row.get("operation_id").getAsString().equals(refill.toString())).toList();
            helper.assertValueEqual(2, refillEntries.size(), "Whole-shop refill must group its two changed variants under one operation.");
            helper.assertTrue(refillEntries.stream().allMatch(row -> row.get("action").getAsString().equals("restock_shop")
                    && row.get("stock_after").getAsInt() == 5), "Each whole-shop event must describe its resulting full stock.");

            UUID bulk = UUID.randomUUID();
            int onlineCount = server.getPlayerList().getPlayers().size();
            AdminLiveService.execute(admin, bulk, "set_stock", shopId, admin.getStringUUID(), "gems", first.stockKey(), 2, false, "", true);
            List<JsonObject> bulkEntries = stockHistory(shopId).stream()
                    .filter(row -> row.get("operation_id").getAsString().equals(bulk.toString())).toList();
            helper.assertValueEqual(onlineCount, bulkEntries.size(), "A bulk edit must identify every changed online owner under one operation.");
            helper.assertTrue(bulkEntries.stream().anyMatch(row -> row.get("player_uuid").getAsString().equals(other.getStringUUID())), "Bulk history must include the other online player.");
            helper.assertValueEqual(5, data.resolveStock(offline, shop, first, System.currentTimeMillis()), "Online bulk edits must not affect offline owners.");

            int beforeAutomatic = stockHistory(shopId).size();
            data.setRemainingStock(offline, shop, first, 0, System.currentTimeMillis() - 61_000L);
            data.resolveStock(offline, shop, first, System.currentTimeMillis());
            helper.assertValueEqual(beforeAutomatic, stockHistory(shopId).size(), "An automatic interval refill must not create manual history.");
            data.setRemainingStock(offline, shop, first, 0, System.currentTimeMillis() - 61_000L);
            request(admin, "set_stock", shopId, offline.toString(), "gems", first.stockKey(), 1, false, "", false);
            helper.assertValueEqual(2, stockHistory(shopId).getLast().get("stock_before").getAsInt(), "Pending automatic stock must be settled before measuring the manual delta.");
            helper.assertValueEqual(1, stockHistory(shopId).getLast().get("stock_after").getAsInt(), "Only the subsequent manual quantity must be recorded.");

            int beforeDisabled = stockHistory(shopId).size();
            events.addProperty("stock_changed", false);
            Files.writeString(AuditFiles.getConfigFile(), config.toString());
            AuditLogService.reload();
            request(admin, "set_stock", shopId, offline.toString(), "gems", first.stockKey(), 4, false, "", false);
            helper.assertValueEqual(4, data.resolveStock(offline, shop, first, System.currentTimeMillis()), "Disabling history must preserve the manual edit itself.");
            helper.assertValueEqual(beforeDisabled, stockHistory(shopId).size(), "Disabling manual stock history must suppress its entries.");
        } finally {
            Files.writeString(AuditFiles.getConfigFile(), originalAuditConfig);
            AuditLogService.reload();
            cleanup(helper, admin, shopId);
        }
        helper.succeed();
    }

    private static List<JsonObject> stockHistory(String shopId) throws Exception {
        AuditLogService.prepareRead();
        if (!Files.exists(AuditLogService.getLogFile())) return List.of();
        try (var lines = Files.lines(AuditLogService.getLogFile())) {
            return lines.map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(event -> event.get("type").getAsString().equals("stock_changed")
                            && event.get("shop_id").getAsString().equals(shopId)).toList();
        }
    }

    private static GameTestPlayer admin(ExtendedGameTestHelper helper) {
        GameTestPlayer admin = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        // GameTestServer's default operator level is 0; explicitly grant the level under test.
        helper.getLevel().getServer().getPlayerList().getOps().add(new ServerOpListEntry(admin.getGameProfile(), 2, false));
        return admin;
    }

    private static ShopDefinition setupShop(ExtendedGameTestHelper helper, String shopId) throws Exception {
        CommandShopGameTestSupport.writeShopJson(shopId, """
                { "categories": [{ "name": "Live administration", "offers": [
                  { "id": "gems", "match": { "include": [
                    { "item": "minecraft:emerald" }, { "item": "minecraft:diamond" }
                  ] }, "price": 5, "stock": 5 },
                  { "id": "unlimited", "match": { "include": [{ "item": "minecraft:stone" }] }, "price": 1 }
                ] }] }
                """);
        ShopVisibilityData.get(helper.getLevel().getServer()).enable(shopId);
        ShopRegistry.reload(helper.getLevel().registryAccess());
        return ShopRegistry.getShop(shopId);
    }

    private static void cleanup(ExtendedGameTestHelper helper, GameTestPlayer admin, String shopId) throws Exception {
        CommandShopSessions.cleanupPlayer(admin.getUUID());
        var server = helper.getLevel().getServer();
        server.getPlayerList().deop(admin.getGameProfile());
        ShopVisibilityData.get(server).enable(shopId);
        CommandShopGameTestSupport.cleanupShop(shopId);
        ShopRegistry.reload(helper.getLevel().registryAccess());
    }

    private static JsonObject request(GameTestPlayer admin, String action, String shopId, String playerId,
                                      String offerId, String stockKey, int amount, boolean enabled, String message, boolean allPlayers) {
        return AdminLiveService.execute(admin, action, shopId, playerId, offerId, stockKey, amount, enabled, message, allPlayers);
    }

    private static void assertRejected(ExtendedGameTestHelper helper, Runnable request) {
        boolean rejected = false;
        try {
            request.run();
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "The invalid live mutation must be rejected.");
    }
}
