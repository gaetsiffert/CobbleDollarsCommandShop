package fr.cobbledollars.commandshops.gametest;

import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.network.AdminNetwork;
import fr.cobbledollars.commandshops.client.admin.AdminClientBridge;
import fr.cobbledollars.commandshops.client.admin.AdminEditorDocument;
import fr.cobbledollars.commandshops.client.admin.CommandShopAdminScreen;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenBankPacket;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenShopPacket;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/** Opt-in integration test. Every operation travels over a real dedicated-server TCP connection. */
@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID, value = Dist.CLIENT)
public final class ClientMultiplayerSmokeTest {
    // Exercise ordinary interaction pacing while retaining the production burst/replay protections.
    private static final long LIVE_OPERATION_PAUSE_MILLIS = 1_000L;
    private static long started;
    private static long since;
    private static int stage;
    private static boolean finished;
    private static CommandShopAdminScreen admin;
    private static UUID merchant;

    private ClientMultiplayerSmokeTest() { }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("commandshops.multiplayerSmokeClient") || finished) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (started == 0) started = System.currentTimeMillis();
            require(System.currentTimeMillis() - started < 180_000L, "Timed out at stage " + stage + ": " + minecraft.screen);
            switch (stage) {
                case 0 -> {
                    if (!(minecraft.screen instanceof TitleScreen) || minecraft.getOverlay() != null) return;
                    String address = System.getProperty("commandshops.multiplayerSmokeAddress", "127.0.0.1:25576");
                    ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address),
                            new ServerData("Release integration verification", address, ServerData.Type.OTHER), false, null);
                    next();
                }
                case 1 -> {
                    if (minecraft.player == null || minecraft.getConnection() == null || elapsed() < 1500L) return;
                    minecraft.getConnection().sendCommand("cdshops admin");
                    next();
                }
                case 2 -> {
                    if (!(minecraft.screen instanceof CommandShopAdminScreen screen)) return;
                    admin = screen;
                    if (flag(admin, "livePending") || field(admin, "liveOverview") == null) return;
                    require(price() == 5, "Initial fixture was not loaded from the server.");
                    setPrice(6); call(admin, "save"); next();
                }
                case 3 -> {
                    if (flag(admin, "pending")) return;
                    require(!flag(admin, "statusError"), "First network save failed: " + field(admin, "status"));
                    require(price() == 6, "The saved snapshot did not return over the network.");
                    next();
                }
                case 4 -> {
                    if (elapsed() < 2000L || flag(admin, "livePending")) return;
                    setPrice(8); call(admin, "save"); next();
                }
                case 5 -> {
                    if (flag(admin, "pending")) return;
                    require(flag(admin, "statusError"), "A stale save must be rejected after the second administrator's change.");
                    require(price() == 8, "The rejected save replaced the local draft.");
                    CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_CLIENT_CONFLICT: stale revision rejected and local draft retained: {}", field(admin, "status"));
                    UUID id = AdminClientBridge.beginOperation(admin, admin.currentDraft(), false);
                    set(admin, "pending", true); set(admin, "pendingOperation", id);
                    AdminNetwork.requestSnapshot(id);
                    next();
                }
                case 6 -> {
                    if (flag(admin, "pending")) return;
                    require(price() == 7, "Reload did not recover the second administrator's applied price.");
                    require(!flag(admin, "statusError"), "Reload failed.");
                    setPrice(8); call(admin, "save"); next();
                }
                case 7 -> {
                    if (flag(admin, "pending") || flag(admin, "livePending")) return;
                    require(!flag(admin, "statusError") && price() == 8, "Saving after conflict recovery failed.");
                    call(admin, "selectTab", 2);
                    call(admin, "selectFile", DedicatedMultiplayerSmokeTest.SHOP_FILE);
                    next();
                }
                case 8 -> {
                    if (flag(admin, "livePending") || stock() == null) return;
                    requireLiveSuccess("Loading stock");
                    require(stock().getAsJsonArray("offers").size() == 1, "Stock response did not contain the fixture offer.");
                    if (elapsed() < LIVE_OPERATION_PAUSE_MILLIS) return;
                    live("set_stock", 2, true); next();
                }
                case 9 -> {
                    if (flag(admin, "livePending")) return;
                    requireLiveSuccess("Manual stock update");
                    require(currentStock() == 2, "Manual stock update returned the wrong amount.");
                    if (elapsed() < LIVE_OPERATION_PAUSE_MILLIS) return;
                    live("restock_offer", 0, true); next();
                }
                case 10 -> {
                    if (flag(admin, "livePending")) return;
                    requireLiveSuccess("Manual stock refill");
                    require(currentStock() == 5, "Manual stock refill returned the wrong amount.");
                    if (elapsed() < LIVE_OPERATION_PAUSE_MILLIS) return;
                    live("visibility", 0, false); next();
                }
                case 11 -> {
                    if (flag(admin, "livePending")) return;
                    requireLiveSuccess("Disabling the shop");
                    require(!shopEnabled(), "Shop was not disabled.");
                    if (elapsed() < LIVE_OPERATION_PAUSE_MILLIS) return;
                    live("visibility", 0, true); next();
                }
                case 12 -> {
                    if (flag(admin, "livePending")) return;
                    requireLiveSuccess("Re-enabling the shop");
                    require(shopEnabled(), "Shop was not re-enabled.");
                    require(document().getDirtyCount() == 0, "Runtime operations changed the configuration draft.");
                    if (elapsed() < LIVE_OPERATION_PAUSE_MILLIS) return;
                    call(admin, "selectTab", 3); next();
                }
                case 13 -> {
                    Object audit = field(admin, "audit");
                    if (flag(audit, "pending")) return;
                    require(field(audit, "data") != null, "Audit summary did not load: " + field(audit, "error"));
                    if (elapsed() < LIVE_OPERATION_PAUSE_MILLIS) return;
                    call(audit, "select", "journal"); next();
                }
                case 14 -> {
                    Object audit = field(admin, "audit");
                    if (flag(audit, "pending")) return;
                    JsonObject data = (JsonObject) field(audit, "data");
                    require(data != null && data.getAsJsonArray("rows").size() > 0, "The audit journal is empty.");
                    String entries = data.toString();
                    require(entries.contains("config_changed") && entries.contains("stock_changed"), "The journal omitted manual configuration or stock history.");
                    minecraft.setScreen(null);
                    minecraft.getConnection().sendCommand("cdshops open " + DedicatedMultiplayerSmokeTest.SHOP_ID);
                    next();
                }
                case 15 -> {
                    if (!(minecraft.player.containerMenu instanceof ShopMenu shop)) return;
                    if (elapsed() < 500L) return;
                    require(DedicatedMultiplayerSmokeTest.isFixtureShop(shop), "Opening the fixture returned different category, item, price or stock.");
                    merchant = shop.getCobbleMerchant().getMerchantUUID();
                    require(DedicatedMultiplayerSmokeTest.isCommandSessionUuid(merchant), "The shop did not receive its command-session UUID.");
                    // The real native bank button closes its shop screen before sending OpenBankPacket.
                    minecraft.screen.onClose();
                    new OpenBankPacket(merchant).sendToServer(); next();
                }
                case 16 -> {
                    if (!(minecraft.player.containerMenu instanceof BankMenu bank)) return;
                    // Leave the native bank open long enough for an independent server tick to observe it.
                    if (elapsed() < 500L) return;
                    require(merchant.equals(bank.getCobbleMerchant().getMerchantUUID()), "The bank belongs to a different merchant.");
                    new OpenShopPacket(merchant).sendToServer(); next();
                }
                case 17 -> {
                    if (!(minecraft.player.containerMenu instanceof ShopMenu shop)) return;
                    if (elapsed() < 500L) return;
                    UUID reopenedMerchant = shop.getCobbleMerchant().getMerchantUUID();
                    require(!merchant.equals(reopenedMerchant) && DedicatedMultiplayerSmokeTest.isCommandSessionUuid(reopenedMerchant),
                            "Returning from the bank did not create a fresh command session.");
                    require(DedicatedMultiplayerSmokeTest.isFixtureShop(shop), "Returning from the bank changed the configured category, item, price or stock.");
                    finished = true;
                    CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_CLIENT_RESULT: PASS; real dedicated connection, admin snapshot/save, concurrent revision conflict/recovery, manual stock/refill, visibility, audit summary/journal and shop-bank-shop transition.");
                    minecraft.stop();
                }
                default -> throw new AssertionError("Unknown stage " + stage);
            }
        } catch (Exception | AssertionError exception) {
            finished = true;
            CobbleDollarsCommandShopsMod.LOGGER.error("MULTIPLAYER_CLIENT_RESULT: FAIL at stage " + stage, exception);
            minecraft.stop();
        }
    }

    private static void live(String action, int amount, boolean enabled) throws Exception {
        JsonObject offer = stock().getAsJsonArray("offers").get(0).getAsJsonObject();
        call(admin, "requestLive", action, DedicatedMultiplayerSmokeTest.SHOP_ID,
                stock().get("playerId").getAsString(), action.equals("visibility") ? "" : offer.get("offerId").getAsString(),
                action.equals("visibility") ? "" : offer.get("stockKey").getAsString(), amount, enabled, "Release verification", false);
    }

    private static boolean shopEnabled() throws Exception {
        for (var row : ((JsonObject) field(admin, "liveOverview")).getAsJsonArray("shops")) {
            JsonObject shop = row.getAsJsonObject();
            if (DedicatedMultiplayerSmokeTest.SHOP_ID.equals(shop.get("id").getAsString())) return shop.get("enabled").getAsBoolean();
        }
        throw new AssertionError("Fixture missing from the live shop overview.");
    }

    private static void requireLiveSuccess(String action) throws Exception {
        require(!flag(admin, "statusError"), action + " failed: " + field(admin, "status"));
    }

    private static void next() { stage++; since = System.currentTimeMillis(); CobbleDollarsCommandShopsMod.LOGGER.info("MULTIPLAYER_CLIENT_STAGE: {}", stage); }
    private static long elapsed() { return System.currentTimeMillis() - since; }
    private static AdminEditorDocument document() throws Exception { return (AdminEditorDocument) field(admin, "document"); }
    private static JsonObject stock() throws Exception { return (JsonObject) field(admin, "liveStock"); }
    private static int currentStock() throws Exception { return stock().getAsJsonArray("offers").get(0).getAsJsonObject().get("current").getAsInt(); }
    private static int price() throws Exception { return DedicatedMultiplayerSmokeTest.offer(document().getFiles().getAsJsonObject(DedicatedMultiplayerSmokeTest.SHOP_FILE)).get("price").getAsInt(); }
    private static void setPrice(int value) throws Exception { DedicatedMultiplayerSmokeTest.offer(document().getFiles().getAsJsonObject(DedicatedMultiplayerSmokeTest.SHOP_FILE)).addProperty("price", value); }
    private static boolean flag(Object object, String name) throws Exception { return (boolean) field(object, name); }
    private static Object field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static Object call(Object object, String name, Object... args) throws Exception {
        for (Method method : object.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true); return method.invoke(object, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
