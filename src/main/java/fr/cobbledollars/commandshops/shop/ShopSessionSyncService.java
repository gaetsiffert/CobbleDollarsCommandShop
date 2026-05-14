package fr.cobbledollars.commandshops.shop;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.network.ClientUiSync;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import fr.harmex.cobbledollars.common.network.packets.s2c.SyncShopConfigPacket;
import fr.harmex.cobbledollars.common.network.packets.s2c.SyncShopPacket;
import fr.harmex.cobbledollars.common.network.packets.s2c.UpdateStockPacket;
import fr.harmex.cobbledollars.common.world.entity.ClientSideCobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

final class ShopSessionSyncService {
    private static final Map<ShopOfferDefinition, List<ShopUiStatePayload.BonusState>> ENCODED_BONUS_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ShopSessionSyncService() {
    }

    static void sendFullSync(ServerPlayer player, CommandShopSessionState session, ShopDefinition.RuntimeShopData runtimeData) {
        Shop runtimeShop = runtimeData.shop();
        ShopMenu shopMenu = requireShopMenu(player);
        CobbleDollarsShopHolder holder = ensureHolder(shopMenu);
        holder.setMerchantUUID(session.sessionUuid());
        holder.setShop(runtimeShop);
        shopMenu.setCobbleMerchant(holder);
        shopMenu.setHasMerchant(false);
        shopMenu.setShop(runtimeShop);
        new SyncShopPacket(shopMenu.containerId, runtimeShop, false, session.sessionUuid(), false).sendToPlayer(player);
        session.setLastSnapshot(runtimeData.sessionSnapshot());
    }

    static void refreshSessionShop(ServerPlayer player, CommandShopSessionState session, ShopDefinition.RuntimeShopData runtimeData) {
        Shop runtimeShop = runtimeData.shop();
        ShopMenu shopMenu = requireShopMenu(player);
        CobbleDollarsShopHolder holder = ensureHolder(shopMenu);
        ShopSessionSnapshot previousSnapshot = session.lastSnapshot();
        ShopSessionSnapshot currentSnapshot = runtimeData.sessionSnapshot();

        holder.setMerchantUUID(session.sessionUuid());
        holder.setShop(runtimeShop);
        shopMenu.setCobbleMerchant(holder);
        shopMenu.setHasMerchant(false);
        shopMenu.setShop(runtimeShop);

        if (previousSnapshot == null || !previousSnapshot.hasSameLayout(currentSnapshot)) {
            sendFullSync(player, session, runtimeData);
            return;
        }

        for (int categoryIndex = 0; categoryIndex < runtimeData.categories().size(); categoryIndex++) {
            List<ShopDefinition.RuntimeShopOfferEntry> runtimeOffers = runtimeData.categories().get(categoryIndex).offers();
            for (int offerIndex = 0; offerIndex < runtimeOffers.size(); offerIndex++) {
                Offer runtimeOffer = runtimeOffers.get(offerIndex).runtimeOffer();
                if (previousSnapshot.stockAt(categoryIndex, offerIndex) != runtimeOffer.getStock()) {
                    sendStockUpdate(player, session, categoryIndex, offerIndex, runtimeOffer.getStock());
                }
            }
        }
        session.setLastSnapshot(currentSnapshot);
    }

    static void resyncRejectedOfferState(
            ServerPlayer player,
            CommandShopSessionState session,
            ShopDefinition.RuntimeShopData runtimeData,
            int categoryIndex,
            int offerIndex
    ) {
        Offer runtimeOffer = runtimeData.getRuntimeOffer(categoryIndex, offerIndex);
        if (runtimeOffer == null) {
            sendFullSync(player, session, runtimeData);
        } else {
            sendStockUpdate(player, session, categoryIndex, offerIndex, runtimeOffer.getStock());
        }
        syncClientShopUiState(player, session, runtimeData);
    }

    static void syncClientBankConfig(ServerPlayer player, Shop runtimeShop, BankDefinition.RuntimeBankData runtimeBankData) {
        try {
            new SyncShopConfigPacket(runtimeShop, runtimeBankData.bank()).sendToPlayer(player);
            ClientUiSync.sendBankUiState(player, runtimeBankData);
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to sync custom bank config for {}", player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.bank_config_load_failed", exception.getMessage()));
        }
    }

    static long syncClientShopUiState(ServerPlayer player, CommandShopSessionState session, ShopDefinition.RuntimeShopData runtimeData) {
        return syncClientShopUiState(player, session, runtimeData, null, 0L, null);
    }

    static long syncClientShopUiState(
            ServerPlayer player,
            CommandShopSessionState session,
            ShopDefinition.RuntimeShopData runtimeData,
            PlayerShopStockData stockData,
            long nowMillis
    ) {
        return syncClientShopUiState(player, session, runtimeData, stockData, nowMillis, null);
    }

    static long syncClientShopUiState(
            ServerPlayer player,
            CommandShopSessionState session,
            ShopDefinition.RuntimeShopData runtimeData,
            PlayerShopStockData stockData,
            long nowMillis,
            ShopUiSyncMetrics metrics
    ) {
        if (runtimeData == null) {
            return Long.MAX_VALUE;
        }

        ShopDefinition shop = ShopRegistry.getShop(session.shopId());
        if (shop == null) {
            return Long.MAX_VALUE;
        }

        long phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ArrayList<ShopUiStatePayload.OfferState> offers = new ArrayList<>(runtimeData.visibleOfferCount());
        long nextRestockAtMillis = Long.MAX_VALUE;
        for (int visibleCategoryIndex = 0; visibleCategoryIndex < runtimeData.categories().size(); visibleCategoryIndex++) {
            ShopDefinition.RuntimeCategory category = runtimeData.categories().get(visibleCategoryIndex);
            for (int visibleOfferIndex = 0; visibleOfferIndex < category.offers().size(); visibleOfferIndex++) {
                ShopDefinition.RuntimeShopOfferEntry entry = category.offers().get(visibleOfferIndex);
                Offer runtimeOffer = entry.runtimeOffer();
                PlayerShopStockData.RestockPreview preview = entry.restockPreview();
                if (stockData != null) {
                    PlayerShopStockData.OfferRuntimeState offerState =
                            stockData.resolveOfferRuntimeState(player.getUUID(), shop, entry.resolvedOffer(), nowMillis);
                    runtimeOffer.setStock(offerState.stock());
                    preview = offerState.restockPreview();
                }
                if (preview.hasNextRestock()) {
                    nextRestockAtMillis = Math.min(nextRestockAtMillis, preview.nextRestockAtMillis());
                }
                offers.add(new ShopUiStatePayload.OfferState(
                        visibleCategoryIndex,
                        visibleOfferIndex,
                        runtimeOffer.getStock(),
                        preview.hasNextRestock() ? preview.nextRestockAtMillis() : -1L,
                        preview.nextRestockAmount(),
                        resolveRestockZoneId(preview),
                        encodeBonusStates(entry.resolvedOffer())
                ));
            }
        }
        if (metrics != null) {
            metrics.buildOfferStatesNanos = System.nanoTime() - phaseStartNanos;
        }

        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ClientUiSync.sendShopUiState(player, session.sessionUuid(), offers);
        if (metrics != null) {
            metrics.sendPayloadNanos = System.nanoTime() - phaseStartNanos;
        }
        return nextRestockAtMillis;
    }

    private static void sendStockUpdate(ServerPlayer player, CommandShopSessionState session, int categoryIndex, int offerIndex, int stock) {
        new UpdateStockPacket(categoryIndex, offerIndex, stock, session.sessionUuid()).sendToPlayer(player);
    }

    private static ShopMenu requireShopMenu(ServerPlayer player) {
        if (player.containerMenu instanceof ShopMenu shopMenu) {
            return shopMenu;
        }
        throw new IllegalStateException("Player is no longer viewing a CobbleDollars shop.");
    }

    private static CobbleDollarsShopHolder ensureHolder(ShopMenu shopMenu) {
        CobbleDollarsShopHolder holder = shopMenu.getCobbleMerchant();
        if (holder == null) {
            holder = new ClientSideCobbleDollarsShopHolder();
        }
        if (holder.getTradingPlayers() == null) {
            holder.setTradingPlayers(new HashSet<Player>());
        }
        return holder;
    }

    private static String resolveRestockZoneId(PlayerShopStockData.RestockPreview preview) {
        if (!preview.hasNextRestock()) {
            return null;
        }
        if (preview.rule() instanceof RestockRule.DailyRestockRule dailyRule) {
            return dailyRule.timeZone();
        }
        return ZoneId.systemDefault().getId();
    }

    private static List<ShopUiStatePayload.BonusState> encodeBonusStates(ResolvedShopOffer resolvedOffer) {
        ShopOfferDefinition sourceOffer = resolvedOffer.source();
        List<PurchaseBonusDefinition> purchaseBonuses = sourceOffer.purchaseBonuses();
        if (purchaseBonuses.isEmpty()) {
            return List.of();
        }

        synchronized (ENCODED_BONUS_STATES) {
            List<ShopUiStatePayload.BonusState> cachedStates = ENCODED_BONUS_STATES.get(sourceOffer);
            if (cachedStates != null) {
                return cachedStates;
            }
            List<ShopUiStatePayload.BonusState> encodedStates = createBonusStates(purchaseBonuses);
            ENCODED_BONUS_STATES.put(sourceOffer, encodedStates);
            return encodedStates;
        }
    }

    private static List<ShopUiStatePayload.BonusState> createBonusStates(List<PurchaseBonusDefinition> purchaseBonuses) {
        ArrayList<ShopUiStatePayload.BonusState> bonusStates = new ArrayList<>(purchaseBonuses.size());
        for (PurchaseBonusDefinition purchaseBonus : purchaseBonuses) {
            ArrayList<ShopUiStatePayload.RewardState> rewards = new ArrayList<>(purchaseBonus.rewards().size());
            for (RewardStackDefinition reward : purchaseBonus.rewards()) {
                rewards.add(new ShopUiStatePayload.RewardState(reward.template()));
            }
            bonusStates.add(new ShopUiStatePayload.BonusState(purchaseBonus.requiredBundles(), rewards));
        }
        return List.copyOf(bonusStates);
    }

    static final class ShopUiSyncMetrics {
        long buildOfferStatesNanos;
        long sendPayloadNanos;
    }
}
