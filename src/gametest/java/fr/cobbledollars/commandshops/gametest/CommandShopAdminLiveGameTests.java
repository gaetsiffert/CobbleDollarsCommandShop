package fr.cobbledollars.commandshops.gametest;

import java.util.UUID;

import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.admin.runtime.AdminLiveService;
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
