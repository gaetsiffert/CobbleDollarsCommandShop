package fr.cobbledollars.commandshops.feedback;

import java.io.IOException;
import java.math.BigInteger;

import fr.cobbledollars.commandshops.network.ClientUiSync;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

public final class ShopFeedbackService {
    private static final int SUCCESS_COLOR = 8453888;
    private static final int FAILURE_COLOR = 16755370;
    private static FeedbackConfig config = FeedbackConfig.defaults();

    private ShopFeedbackService() {
    }

    public static void initialize() throws IOException {
        FeedbackFiles.ensureDefaultConfigExists();
        reload();
    }

    public static void reload() throws IOException {
        config = FeedbackFiles.loadConfig();
    }

    public static void clear() {
        config = FeedbackConfig.defaults();
    }

    public static void onShopDenied(ServerPlayer player, ShopDefinition shop) {
        emit(player, config.shopDenied(), FeedbackFormatter.shopDenied(shop), FAILURE_COLOR, 3200);
    }

    public static void onBuySuccess(
            ServerPlayer player,
            ItemStack bundleTemplate,
            int amount,
            BigInteger totalPrice,
            int stockLeft,
            PlayerShopStockData.RestockPreview restockPreview,
            long nowMillis
    ) {
        emit(player, config.buySuccess(), FeedbackFormatter.buySuccess(
                bundleTemplate,
                amount,
                totalPrice,
                stockLeft
        ), SUCCESS_COLOR, 2800);
    }

    public static void onBuyFailure(
            ServerPlayer player,
            BuyFailureReason reason,
            PlayerShopStockData.RestockPreview restockPreview,
            long nowMillis
    ) {
        emit(player, config.buyFailure(), FeedbackFormatter.buyFailure(
                reason
        ), FAILURE_COLOR, 3200);
    }

    public static void onSellSuccess(ServerPlayer player, int soldItemCount, BigInteger totalValue) {
        emit(player, config.sellSuccess(), FeedbackFormatter.sellSuccess(soldItemCount, totalValue), SUCCESS_COLOR, 2800);
    }

    public static void onSellFailure(ServerPlayer player, SellFailureReason reason) {
        emit(player, config.sellFailure(), FeedbackFormatter.sellFailure(reason), FAILURE_COLOR, 3200);
    }

    private static void emit(ServerPlayer player, FeedbackConfig.EventConfig eventConfig, Component message, int color, int ttlMillis) {
        boolean actionBarRequested = eventConfig.channels().contains(FeedbackChannel.ACTION_BAR);
        boolean actionBarVisible = actionBarRequested && player.containerMenu == player.inventoryMenu;
        boolean chatRequested = eventConfig.channels().contains(FeedbackChannel.CHAT) || (actionBarRequested && !actionBarVisible);
        boolean textRequested = actionBarRequested || chatRequested;

        if (textRequested && ClientUiSync.sendOverlayMessage(player, message.getString(), color, ttlMillis)) {
            playSound(player, eventConfig);
            return;
        }

        if (actionBarVisible) {
            player.displayClientMessage(message, true);
        }
        if (chatRequested) {
            player.sendSystemMessage(message);
        }
        playSound(player, eventConfig);
    }

    private static void playSound(ServerPlayer player, FeedbackConfig.EventConfig eventConfig) {
        if (!eventConfig.channels().contains(FeedbackChannel.SOUND) || eventConfig.sound() == null) {
            return;
        }

        FeedbackConfig.SoundSpec sound = eventConfig.sound();
        player.playNotifySound(sound.sound(), SoundSource.PLAYERS, sound.volume(), sound.pitch());
    }
}
