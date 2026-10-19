package fr.cobbledollars.commandshops.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import fr.cobbledollars.commandshops.CobbleDollarsOffers;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.config.AdminConfigService;
import fr.cobbledollars.commandshops.audit.AuditFiles;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.shop.ShopFiles;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.util.UUID;

/** Development-only counterpart of the real TCP client smoke test. Use an isolated game directory. */
@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID)
public final class DedicatedMultiplayerSmokeTest {
    static final String SHOP_ID = "release_multiplayer_smoke";
    static final String SHOP_FILE = "shops/" + SHOP_ID + "/shop.json";
    static final UUID SECOND_ADMIN = UUID.nameUUIDFromBytes("release-smoke-second-admin".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static long started;
    private static boolean secondEdit;
    private static boolean joined;
    private static boolean finished;
    private static UUID realClient;
    private static UUID observedBankMerchant;
    private static boolean observedReturnToShop;

    private DedicatedMultiplayerSmokeTest() { }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("commandshops.multiplayerSmokeServer")) return;
        try {
            var root = ShopFiles.getConfigDirectory();
            // Requiring a sentinel avoids ever changing a normal user's game directory.
            if (!Files.isRegularFile(event.getServer().getServerDirectory().resolve("release-smoke-isolated.txt")))
                throw new IllegalStateException("The multiplayer smoke server requires an isolated game directory sentinel.");
            Files.createDirectories(root.resolve("shops/" + SHOP_ID));
            Files.writeString(root.resolve(SHOP_FILE), """
                    {"categories":[{"name":"Release verification","offers":[
                    {"id":"stone","match":{"include":[{"item":"minecraft:stone"}]},"count":1,"price":5,"stock":5}
                    ]}]}
                    """);
            Files.writeString(root.resolve("shops/" + SHOP_ID + "/bank.json"), """
                    {"offers":[{"match":{"include":[{"item":"minecraft:stone"}]},"price":1}]}
                    """);
            JsonObject audit = JsonParser.parseString(Files.readString(AuditFiles.getConfigFile())).getAsJsonObject();
            audit.addProperty("enabled", true);
            audit.addProperty("flush_each_entry", true);
            JsonObject events = audit.has("events") ? audit.getAsJsonObject("events") : new JsonObject();
            audit.add("events", events);
            events.addProperty("config_changed", true);
            events.addProperty("stock_changed", true);
            events.addProperty("visibility_changed", true);
            Files.writeString(AuditFiles.getConfigFile(), audit.toString());
            AdminConfigService.reload(event.getServer(), "release_smoke_setup", null);
            started = System.currentTimeMillis();
            CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_SERVER_READY: {} shops loaded, fixture {}.", ShopRegistry.listShopIds().size(), SHOP_ID);
        } catch (Exception exception) {
            fail(event.getServer(), exception);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Boolean.getBoolean("commandshops.multiplayerSmokeServer") || !(event.getEntity() instanceof ServerPlayer player)) return;
        // Keep world hazards from interrupting a network/UI verification run.
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        player.setHealth(player.getMaxHealth());
        player.getServer().getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 2, false));
        player.getServer().getPlayerList().sendPlayerPermissionLevel(player);
        joined = true;
        realClient = player.getUUID();
        CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_SERVER_JOIN: real client {} granted operator level 2.", player.getGameProfile().getName());
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("commandshops.multiplayerSmokeServer") || started == 0 || finished) return;
        try {
            if (System.currentTimeMillis() - started > 240_000L) throw new AssertionError("Multiplayer client did not complete within 240 seconds.");
            ServerPlayer connected = realClient == null ? null : event.getServer().getPlayerList().getPlayer(realClient);
            if (connected != null) {
                if (connected.containerMenu instanceof BankMenu bank) {
                    observedBankMerchant = bank.getCobbleMerchant().getMerchantUUID();
                } else if (observedBankMerchant != null && connected.containerMenu instanceof ShopMenu shop
                        && !observedBankMerchant.equals(shop.getCobbleMerchant().getMerchantUUID())
                        && isCommandSessionUuid(shop.getCobbleMerchant().getMerchantUUID()) && isFixtureShop(shop)) {
                    // Native bank return opens a fresh command session for the same configured shop.
                    observedReturnToShop = true;
                }
            }
            if (!secondEdit && price() == 6) {
                JsonObject snapshot = JsonParser.parseString(AdminConfigService.snapshot(event.getServer())).getAsJsonObject();
                offer(snapshot.getAsJsonObject("files").getAsJsonObject(SHOP_FILE)).addProperty("price", 7);
                var actor = new ServerPlayer(event.getServer(), event.getServer().overworld(),
                        new GameProfile(SECOND_ADMIN, "SmokeSecondAdmin"), ClientInformation.createDefault());
                var result = AdminConfigService.save(event.getServer(), snapshot.toString(), actor);
                if (!result.success()) throw new AssertionError("Second administrator edit failed: " + result.message().english());
                secondEdit = true;
                CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_SERVER_SECOND_ADMIN: price 6 -> 7 applied after the real client's snapshot.");
            }
            if (joined && event.getServer().getPlayerList().getPlayerCount() == 0) {
                if (!secondEdit || price() != 8) throw new AssertionError("The connected client left before completing conflict recovery.");
                if (!ShopVisibilityData.get(event.getServer()).status(SHOP_ID).enabled())
                    throw new AssertionError("The connected client left before re-enabling the fixture shop.");
                if (observedBankMerchant == null || !observedReturnToShop)
                    throw new AssertionError("The connected client left before the server observed a native bank and return to its shop.");
                AuditLogService.prepareRead();
                long manual;
                boolean secondActor;
                try (var lines = Files.lines(AuditLogService.getLogFile())) {
                    var rows = lines.map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
                    manual = rows.stream().filter(row -> SHOP_ID.equals(text(row, "shop_id")) && "stock_changed".equals(text(row, "type"))).count();
                    secondActor = rows.stream().anyMatch(row -> SECOND_ADMIN.toString().equals(text(row, "actor_uuid")) && "config_changed".equals(text(row, "type")));
                }
                if (manual < 2 || !secondActor) throw new AssertionError("Expected persisted manual stock and second administrator history.");
                finished = true;
                CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_SERVER_RESULT: PASS; connected client, concurrent administrator conflict, applied price 8, {} manual stock records and second actor history persisted; enabled shop, native bank and return independently observed.", manual);
                event.getServer().halt(false);
            }
        } catch (Exception | AssertionError exception) {
            fail(event.getServer(), exception);
        }
    }

    private static int price() throws Exception {
        return offer(JsonParser.parseString(Files.readString(ShopFiles.getConfigDirectory().resolve(SHOP_FILE))).getAsJsonObject()).get("price").getAsInt();
    }

    static JsonObject offer(JsonObject shop) {
        return shop.getAsJsonArray("categories").get(0).getAsJsonObject().getAsJsonArray("offers").get(0).getAsJsonObject();
    }

    static boolean isCommandSessionUuid(UUID uuid) {
        return uuid != null && (uuid.getMostSignificantBits() & 0xffffffff0000f000L) == 0x4344534800008000L;
    }

    static boolean isFixtureShop(ShopMenu menu) {
        var categories = menu.getShop().getCategories();
        if (categories.size() != 1 || !"Release verification".equals(categories.getFirst().getName())) return false;
        var offers = categories.getFirst().getOffers();
        return offers.size() == 1 && offers.getFirst().getPrice().equals(java.math.BigInteger.valueOf(8))
                && offers.getFirst().getStock() == 5 && CobbleDollarsOffers.getItem(offers.getFirst()).is(Items.STONE);
    }

    private static String text(JsonObject row, String key) { return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : ""; }

    private static void fail(net.minecraft.server.MinecraftServer server, Throwable exception) {
        finished = true;
        CobbleDollarsCommandShopsMod.LOGGER.error("MULTIPLAYER_SERVER_RESULT: FAIL", exception);
        server.halt(false);
    }
}
