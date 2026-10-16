package fr.cobbledollars.commandshops.gametest;

import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.feedback.BuyFailureReason;
import fr.cobbledollars.commandshops.feedback.FeedbackFiles;
import fr.cobbledollars.commandshops.feedback.FeedbackFormatter;
import fr.cobbledollars.commandshops.feedback.SellFailureReason;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "feedback.", groups = "feedback", side = Dist.DEDICATED_SERVER)
public final class CommandShopFeedbackGameTests {
    private static final List<String> EVENTS = List.of("buy_success", "buy_failure", "sell_success", "sell_failure", "shop_denied");

    private CommandShopFeedbackGameTests() {
    }

    @TestHolder(value = "all_events_keep_configured_chat_with_optional_overlay", title = "All feedback events keep configured chat alongside the overlay")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void all_events_keep_configured_chat_with_optional_overlay(ExtendedGameTestHelper helper) throws Exception {
        try (FeedbackFixture fixture = new FeedbackFixture(helper)) {
            fixture.configure(allEvents("chat", "sound"));
            helper.assertTrue(NetworkRegistry.hasChannel(fixture.player.connection, ClientOverlayMessagePayload.TYPE.id()),
                    "The mock client must negotiate the optional overlay channel.");
            ItemStack bundle = new ItemStack(Items.BREAD, 3);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onBuySuccess(player, bundle, 2, BigInteger.valueOf(25), 7),
                    FeedbackFormatter.buySuccess(bundle, 2, BigInteger.valueOf(25), 7), 1, 0, 1, 1);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_MONEY),
                    FeedbackFormatter.buyFailure(BuyFailureReason.NOT_ENOUGH_MONEY), 1, 0, 1, 1);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onSellSuccess(player, 6, BigInteger.valueOf(42)),
                    FeedbackFormatter.sellSuccess(6, BigInteger.valueOf(42)), 1, 0, 1, 1);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onSellFailure(player, SellFailureReason.NOTHING_SELLABLE),
                    FeedbackFormatter.sellFailure(SellFailureReason.NOTHING_SELLABLE), 1, 0, 1, 1);
            Component denial = Component.literal("First failed condition: arena badge required");
            assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onShopDenied(player, denial),
                    denial, 1, 0, 1, 1);
        }
        helper.succeed();
    }

    @TestHolder(value = "shop_menu_keeps_requested_action_bar_and_overlay", title = "An open shop keeps the requested action bar and overlay")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void shop_menu_keeps_requested_action_bar_and_overlay(ExtendedGameTestHelper helper) throws Exception {
        try (FeedbackFixture fixture = new FeedbackFixture(helper)) {
            fixture.openShop(helper);
            fixture.configure(allEvents("action_bar", "sound"));
            Component expected = FeedbackFormatter.buyFailure(BuyFailureReason.OUT_OF_STOCK);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK),
                    expected, 0, 1, 1, 1);

            fixture.configure(allEvents("chat", "action_bar", "sound"));
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK),
                    expected, 1, 1, 1, 1);
        }
        helper.succeed();
    }

    @TestHolder(value = "server_only_client_uses_chat_fallback_only_for_open_menu", title = "Clients without the addon keep visible feedback inside menus")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void server_only_client_uses_chat_fallback_only_for_open_menu(ExtendedGameTestHelper helper) throws Exception {
        try (FeedbackFixture fixture = new FeedbackFixture(helper)) {
            fixture.openShop(helper);
            fixture.configure(allEvents("action_bar", "sound"));
            var connection = fixture.player.connection.getConnection();
            NetworkPayloadSetup previousSetup = ChannelAttributes.getPayloadSetup(connection);
            try {
                ChannelAttributes.setPayloadSetup(connection, NetworkPayloadSetup.empty());
                helper.assertTrue(!NetworkRegistry.hasChannel(fixture.player.connection, ClientOverlayMessagePayload.TYPE.id()),
                        "The fixture must behave like a client without the optional addon channel.");
                Component expected = FeedbackFormatter.sellSuccess(2, BigInteger.valueOf(9));
                assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onSellSuccess(player, 2, BigInteger.valueOf(9)),
                        expected, 1, 1, 0, 1);

                // An explicitly requested chat channel and the fallback must share one packet.
                fixture.configure(allEvents("chat", "action_bar", "sound"));
                assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onSellSuccess(player, 2, BigInteger.valueOf(9)),
                        expected, 1, 1, 0, 1);

                fixture.player.closeContainer();
                fixture.configure(allEvents("action_bar", "sound"));
                assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onSellSuccess(player, 2, BigInteger.valueOf(9)),
                        expected, 0, 1, 0, 1);
            } finally {
                ChannelAttributes.setPayloadSetup(connection, previousSetup);
            }
        }
        helper.succeed();
    }

    @TestHolder(value = "empty_sound_only_and_text_only_channels_are_respected", title = "Empty, sound-only and text-only feedback settings are respected")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void empty_sound_only_and_text_only_channels_are_respected(ExtendedGameTestHelper helper) throws Exception {
        try (FeedbackFixture fixture = new FeedbackFixture(helper)) {
            Component message = Component.literal("Shop temporarily closed");
            fixture.configure(allEvents());
            assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onShopDenied(player, message),
                    message, 0, 0, 0, 0);
            fixture.configure(allEvents("sound"));
            assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onShopDenied(player, message),
                    message, 0, 0, 0, 1);
            fixture.configure(allEvents("chat"));
            assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onShopDenied(player, message),
                    message, 1, 0, 1, 0);
        }
        helper.succeed();
    }

    @TestHolder(value = "event_settings_apply_independently", title = "Each feedback event uses its own delivery settings")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void event_settings_apply_independently(ExtendedGameTestHelper helper) throws Exception {
        try (FeedbackFixture fixture = new FeedbackFixture(helper)) {
            JsonObject config = allEvents();
            config.add("buy_success", event("chat"));
            config.add("buy_failure", event("action_bar"));
            config.add("sell_success", event("sound"));
            config.add("shop_denied", event("chat", "action_bar", "sound"));
            fixture.configure(config);
            ItemStack bundle = new ItemStack(Items.APPLE, 2);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onBuySuccess(player, bundle, 1, BigInteger.TEN, -1),
                    FeedbackFormatter.buySuccess(bundle, 1, BigInteger.TEN, -1), 1, 0, 1, 0);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_SPACE),
                    FeedbackFormatter.buyFailure(BuyFailureReason.NOT_ENOUGH_SPACE), 0, 1, 1, 0);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onSellSuccess(player, 1, BigInteger.ONE),
                    FeedbackFormatter.sellSuccess(1, BigInteger.ONE), 0, 0, 0, 1);
            assertDelivery(helper, fixture.player,
                    player -> ShopFeedbackService.onSellFailure(player, SellFailureReason.NOTHING_SELLABLE),
                    FeedbackFormatter.sellFailure(SellFailureReason.NOTHING_SELLABLE), 0, 0, 0, 0);
            Component denial = Component.literal("Quest missing");
            assertDelivery(helper, fixture.player, player -> ShopFeedbackService.onShopDenied(player, denial),
                    denial, 1, 1, 1, 1);
        }
        helper.succeed();
    }

    private static JsonObject allEvents(String... channels) {
        JsonObject config = new JsonObject();
        for (String name : EVENTS) {
            config.add(name, event(channels));
        }
        return config;
    }

    private static JsonObject event(String... channels) {
        JsonObject config = new JsonObject();
        JsonArray values = new JsonArray();
        for (String channel : channels) {
            values.add(channel);
        }
        config.add("channels", values);
        config.addProperty("sound", "minecraft:entity.villager.no");
        return config;
    }

    private static void assertDelivery(ExtendedGameTestHelper helper, GameTestPlayer player, Consumer<GameTestPlayer> action,
                                       Component expected, int chatCount, int actionBarCount, int overlayCount, int soundCount) {
        player.clearOutboundPackets();
        action.accept(player);
        var nativeMessages = player.getOutboundPackets(ClientboundSystemChatPacket.class).toList();
        var overlays = player.getOutboundPayloads(ClientOverlayMessagePayload.class).toList();
        helper.assertValueEqual(chatCount, (int) nativeMessages.stream().filter(packet -> !packet.overlay()).count(),
                "Incorrect number of configured chat messages.");
        helper.assertValueEqual(actionBarCount, (int) nativeMessages.stream().filter(ClientboundSystemChatPacket::overlay).count(),
                "Incorrect number of configured action-bar messages.");
        helper.assertValueEqual(overlayCount, overlays.size(), "Incorrect number of optional client overlays.");
        helper.assertValueEqual(soundCount, (int) player.getOutboundPackets(ClientboundSoundPacket.class).count(),
                "The configured sound should play exactly once, or stay silent when disabled.");
        for (var packet : nativeMessages) {
            helper.assertTrue(expected.equals(packet.content()), "The native channel must keep the original feedback component.");
        }
        for (var overlay : overlays) {
            helper.assertTrue(expected.equals(overlay.message()), "The optional overlay must keep the same feedback component.");
        }
    }

    private static final class FeedbackFixture implements AutoCloseable {
        private final byte[] originalConfig = Files.readAllBytes(FeedbackFiles.getConfigFile());
        private final GameTestPlayer player;

        private FeedbackFixture(ExtendedGameTestHelper helper) throws Exception {
            player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        }

        private void configure(JsonObject config) throws Exception {
            Files.writeString(FeedbackFiles.getConfigFile(), config.toString());
            ShopFeedbackService.reload();
        }

        private void openShop(ExtendedGameTestHelper helper) {
            CommandShopSessions.openShop(player, ShopRegistry.getShop("general_store"));
            helper.assertTrue(player.containerMenu instanceof ShopMenu, "The fixture must open an actual CobbleDollars shop menu.");
        }

        @Override
        public void close() throws Exception {
            try {
                CommandShopSessions.cleanupPlayer(player.getUUID());
                player.closeContainer();
            } finally {
                Files.write(FeedbackFiles.getConfigFile(), originalConfig);
                ShopFeedbackService.reload();
            }
        }
    }
}
