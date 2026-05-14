package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.StackCountMath;
import fr.cobbledollars.commandshops.feedback.BuyFailureReason;
import fr.cobbledollars.commandshops.feedback.SellFailureReason;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.network.ClientUiSync;
import fr.cobbledollars.commandshops.network.payload.ShopUiStatePayload;
import fr.harmex.cobbledollars.common.network.packets.c2s.BuyPacket;
import fr.harmex.cobbledollars.common.network.packets.s2c.SyncShopConfigPacket;
import fr.harmex.cobbledollars.common.network.packets.s2c.SyncShopPacket;
import fr.harmex.cobbledollars.common.network.packets.s2c.UpdateStockPacket;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.entity.ClientSideCobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class CommandShopSessions {
    private static final Map<UUID, CommandShopSession> ACTIVE_SESSIONS = new HashMap<>();
    private static final Map<UUID, PendingShopOpen> PENDING_SHOP_OPENS = new HashMap<>();
    private static final Map<ShopOfferDefinition, List<ShopUiStatePayload.BonusState>> ENCODED_BONUS_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CommandShopSessions() {
    }

    public static void openShop(ServerPlayer player, ShopDefinition shop) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("Player is not attached to a server.");
        }
        ShopRegistry.ShopAccessResult accessResult = ShopRegistry.evaluateAccess(server, shop, player);
        if (!accessResult.allowed()) {
            ShopFeedbackService.onShopDenied(player, accessResult.denialMessage());
            throw new IllegalStateException("Player does not meet the conditions for shop '" + shop.id() + "'.");
        }

        cleanupPlayer(player.getUUID());

        long nowMillis = System.currentTimeMillis();
        PlayerShopStockData stockData = PlayerShopStockData.get(server);
        ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(stockData, player, nowMillis);
        Shop runtimeShop = runtimeData.shop();
        UUID sessionUuid = UUID.randomUUID();
        CobbleDollarsShopHolder sessionHolder = createSessionHolder(sessionUuid, runtimeShop);
        sessionHolder.getTradingPlayers().add(player);

        PlayerExtensionKt.openShop(player, sessionHolder);
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            throw new IllegalStateException("CobbleDollars did not open a shop menu for this player.");
        }

        CommandShopSession session = new CommandShopSession(shop.id(), sessionUuid, shopMenu.containerId);
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        sendFullSync(player, session, runtimeShop);
        long nextRestockAtMillis = syncClientShopUiState(player, session, runtimeData);
        updateSessionRefreshState(server, player, session, shop, runtimeData, nowMillis, nextRestockAtMillis);
    }

    public static void queueOpenShop(ServerPlayer player, ShopDefinition shop) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("Player is not attached to a server.");
        }

        PENDING_SHOP_OPENS.put(player.getUUID(), new PendingShopOpen(shop.id(), server.getTickCount() + 2L, 5));
    }

    public static boolean openCustomBank(ServerPlayer player, UUID merchantUuid) {
        CommandShopSession session = getSession(player, merchantUuid);
        if (session == null) {
            return false;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        ShopDefinition shop = resolveSessionShop(server, player, session);
        if (shop == null) {
            return true;
        }

        long nowMillis = System.currentTimeMillis();
        PlayerShopStockData stockData = PlayerShopStockData.get(server);
        ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(stockData, player, nowMillis);
        Shop runtimeShop = runtimeData.shop();
        PlayerExtensionKt.openBank(player, createSessionHolder(session.sessionUuid(), runtimeShop));
        syncClientBankConfig(player, runtimeShop, ShopRegistry.getBankDefinition(shop.id()).createRuntimeData(player));
        updateSessionRefreshState(server, player, session, shop, runtimeData, nowMillis, Long.MAX_VALUE);
        return true;
    }

    public static boolean reopenCustomShop(ServerPlayer player, UUID merchantUuid) {
        CommandShopSession session = getSession(player, merchantUuid);
        if (session == null) {
            return false;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        ShopDefinition shop = resolveSessionShop(server, player, session);
        if (shop == null) {
            return true;
        }

        openShop(player, shop);
        return true;
    }

    public static boolean handleCustomBuy(BuyPacket packet, MinecraftServer server, ServerPlayer player) {
        CommandShopSession session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }

        boolean packetTargetsSession = session.sessionUuid().equals(packet.getMerchantUUID());
        boolean viewingSession = isViewingSession(player, session);

        if (!packetTargetsSession) {
            return viewingSession;
        }

        if (!viewingSession) {
            cleanupPlayer(player.getUUID());
            return true;
        }

        ShopDefinition shop = resolveSessionShop(server, player, session);
        if (shop == null) {
            return true;
        }

        PlayerShopStockData stockData = PlayerShopStockData.get(server);
        long nowMillis = System.currentTimeMillis();
        ShopDefinition.RuntimeShopData currentRuntimeData = shop.createRuntimeData(stockData, player, nowMillis);
        Shop currentRuntimeShop = currentRuntimeData.shop();

        ResolvedShopOffer offerDefinition = currentRuntimeData.getResolvedOffer(packet.getCategoryIndex(), packet.getOfferIndex());
        if (offerDefinition == null) {
            sendFullSync(player, session, currentRuntimeShop);
            syncClientShopUiState(player, session, currentRuntimeData);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OFFER_UNAVAILABLE);
            return true;
        }

        Offer expectedOffer = currentRuntimeData.getRuntimeOffer(packet.getCategoryIndex(), packet.getOfferIndex());
        if (expectedOffer == null || !expectedOffer.equalsWithoutStock(packet.getOffer())) {
            sendFullSync(player, session, currentRuntimeShop);
            syncClientShopUiState(player, session, currentRuntimeData);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OFFER_CHANGED);
            return true;
        }

        int requestedAmount = packet.getAmount();
        if (requestedAmount <= 0) {
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.INVALID_AMOUNT);
            return true;
        }
        int amount = requestedAmount;

        int currentStock = expectedOffer.getStock();
        if (currentStock == 0) {
            resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK);
            return true;
        }
        if (currentStock > 0 && amount > currentStock) {
            resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK);
            return true;
        }

        BigInteger totalPrice = expectedOffer.getPrice().multiply(BigInteger.valueOf(amount));
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player);
        if (balance.compareTo(totalPrice) < 0) {
            resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_MONEY);
            return true;
        }

        PurchaseExecutionPlan purchasePlan = buildPurchaseExecutionPlan(offerDefinition, expectedOffer, amount);
        if (purchasePlan == null) {
            resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.INVALID_AMOUNT);
            return true;
        }

        List<ItemStack> bonusItems = purchasePlan.bonusItems();
        InventorySnapshot inventorySnapshot = InventorySnapshot.capture(player);
        InventorySnapshot deliveredInventory = inventorySnapshot.planDelivery(purchasePlan.deliveries());
        if (deliveredInventory == null) {
            resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_SPACE);
            return true;
        }

        if (offerDefinition.hasFiniteStock()) {
            stockData.consumeStock(player.getUUID(), shop, offerDefinition, amount, nowMillis);
        }

        PlayerExtensionKt.setCobbleDollars(player, balance.subtract(totalPrice));
        deliveredInventory.apply(player.getInventory());
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();

        int updatedStock = -1;
        if (offerDefinition.hasFiniteStock()) {
            updatedStock = Math.max(0, currentStock - amount);
            expectedOffer.setStock(updatedStock);
        }
        refreshSessionShop(player, session, currentRuntimeData.shop());
        long nextRestockAtMillis = syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);
        ShopFeedbackService.onBuySuccess(player, expectedOffer.getItem(), amount, totalPrice, updatedStock);
        TransactionAuditLogger.logBuySuccess(player, shop, offerDefinition, amount, totalPrice, bonusItems);
        updateSessionRefreshState(server, player, session, shop, currentRuntimeData, nowMillis, nextRestockAtMillis);
        return true;
    }

    public static void refreshPlayerSession(ServerPlayer player) {
        refreshPlayerSession(player, null);
    }

    public static RefreshBreakdown measureRefreshPlayerSession(ServerPlayer player) {
        RefreshMetrics metrics = new RefreshMetrics();
        long totalStartNanos = System.nanoTime();
        refreshPlayerSession(player, metrics);
        metrics.totalNanos = System.nanoTime() - totalStartNanos;
        return metrics.toBreakdown();
    }

    private static void refreshPlayerSession(ServerPlayer player, RefreshMetrics metrics) {
        CommandShopSession session = ACTIVE_SESSIONS.get(player.getUUID());
        MinecraftServer server = player.getServer();
        if (session == null || server == null) {
            return;
        }

        long phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ShopDefinition shop = resolveSessionShop(server, player, session);
        if (metrics != null) {
            metrics.resolveSessionShopNanos = System.nanoTime() - phaseStartNanos;
        }
        if (shop == null) {
            return;
        }

        PlayerShopStockData stockData = PlayerShopStockData.get(server);
        long nowMillis = System.currentTimeMillis();
        ShopDefinition.CreateRuntimeDataMetrics runtimeMetrics =
                metrics == null ? null : new ShopDefinition.CreateRuntimeDataMetrics();
        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(stockData, player, nowMillis, runtimeMetrics);
        if (metrics != null) {
            metrics.createRuntimeDataNanos = System.nanoTime() - phaseStartNanos;
            metrics.createRuntimeDataContextNanos = runtimeMetrics.contextNanos();
            metrics.createRuntimeDataCandidateSelectionNanos = runtimeMetrics.candidateSelectionNanos();
            metrics.createRuntimeDataMaterializationNanos = runtimeMetrics.materializationNanos();
        }
        Shop runtimeShop = runtimeData.shop();
        if (isViewingSessionShop(player, session)) {
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            refreshSessionShop(player, session, runtimeShop);
            if (metrics != null) {
                metrics.refreshSessionShopNanos = System.nanoTime() - phaseStartNanos;
            }
            ShopUiSyncMetrics syncMetrics = metrics == null ? null : new ShopUiSyncMetrics();
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            long nextRestockAtMillis = syncClientShopUiState(player, session, runtimeData, null, 0L, syncMetrics);
            if (metrics != null) {
                metrics.syncClientShopUiStateNanos = System.nanoTime() - phaseStartNanos;
                metrics.syncClientShopUiStateBuildNanos = syncMetrics.buildOfferStatesNanos;
                metrics.syncClientShopUiStateSendNanos = syncMetrics.sendPayloadNanos;
            }
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            updateSessionRefreshState(server, player, session, shop, runtimeData, nowMillis, nextRestockAtMillis);
            if (metrics != null) {
                metrics.updateSessionRefreshStateNanos = System.nanoTime() - phaseStartNanos;
            }
            return;
        }
        if (isViewingSessionBank(player, session)) {
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            BankDefinition.RuntimeBankData runtimeBankData = ShopRegistry.getBankDefinition(shop.id()).createRuntimeData(player);
            if (metrics != null) {
                metrics.createRuntimeBankDataNanos = System.nanoTime() - phaseStartNanos;
            }
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            syncClientBankConfig(player, runtimeShop, runtimeBankData);
            if (metrics != null) {
                metrics.syncClientBankConfigNanos = System.nanoTime() - phaseStartNanos;
            }
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            updateSessionRefreshState(server, player, session, shop, runtimeData, nowMillis, Long.MAX_VALUE);
            if (metrics != null) {
                metrics.updateSessionRefreshStateNanos = System.nanoTime() - phaseStartNanos;
            }
        }
    }

    public static void refreshAllSessions(MinecraftServer server) {
        for (UUID playerUuid : ACTIVE_SESSIONS.keySet().toArray(UUID[]::new)) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerUuid);
            if (player == null) {
                ACTIVE_SESSIONS.remove(playerUuid);
                continue;
            }
            refreshPlayerSession(player);
        }
    }

    public static void tick(MinecraftServer server) {
        processPendingShopOpens(server);

        long currentTick = server.getTickCount();
        Iterator<Map.Entry<UUID, CommandShopSession>> iterator = ACTIVE_SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CommandShopSession> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !isViewingSession(player, entry.getValue())) {
                iterator.remove();
                continue;
            }

            ResourceLocation currentDimension = player.level().dimension().location();
            if (currentTick >= entry.getValue().nextRefreshTick()
                    || shouldRefreshForDimensionChange(player, entry.getValue(), currentDimension)) {
                refreshPlayerSession(player);
            }
        }
    }

    public static void cleanupPlayer(UUID playerUuid) {
        ACTIVE_SESSIONS.remove(playerUuid);
        PENDING_SHOP_OPENS.remove(playerUuid);
    }

    public static void cleanupAll() {
        ACTIVE_SESSIONS.clear();
        PENDING_SHOP_OPENS.clear();
    }

    public static void closeShopSessions(MinecraftServer server, String shopId, Component denialMessage) {
        String normalizedShopId = ShopFiles.normalizeId(shopId, "shop id");
        Iterator<Map.Entry<UUID, PendingShopOpen>> pendingIterator = PENDING_SHOP_OPENS.entrySet().iterator();
        while (pendingIterator.hasNext()) {
            if (pendingIterator.next().getValue().shopId().equals(normalizedShopId)) {
                pendingIterator.remove();
            }
        }

        Iterator<Map.Entry<UUID, CommandShopSession>> iterator = ACTIVE_SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CommandShopSession> entry = iterator.next();
            if (!entry.getValue().shopId().equals(normalizedShopId)) {
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            iterator.remove();
            if (player == null) {
                continue;
            }
            player.closeContainer();
            ShopFeedbackService.onShopDenied(player, denialMessage);
        }
    }

    public static boolean handleCustomSell(MinecraftServer server, ServerPlayer player) {
        CommandShopSession session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null || !isViewingSessionBank(player, session)) {
            return false;
        }

        ShopDefinition shop = resolveSessionShop(server, player, session);
        if (shop == null) {
            return true;
        }

        BankMenu bankMenu = requireBankMenu(player);
        SimpleContainer bankContainer = bankMenu.getBankContainer();
        BigInteger totalValue = BigInteger.ZERO;
        int soldItemCount = 0;
        ArrayList<TransactionAuditLogger.SoldItemLine> soldItems = new ArrayList<>();
        try {
            var bankData = ShopRegistry.getBankDefinition(shop.id()).createRuntimeData(player);
            for (int slot = 0; slot < bankContainer.getContainerSize(); slot++) {
                ItemStack stack = bankContainer.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                Offer offer = bankData.get(stack);
                if (offer == null) {
                    continue;
                }

                BigInteger lineTotal = offer.getPrice().multiply(BigInteger.valueOf(stack.getCount()));
                totalValue = totalValue.add(lineTotal);
                soldItemCount += stack.getCount();
                soldItems.add(new TransactionAuditLogger.SoldItemLine(stack.copy(), offer.getPrice(), lineTotal));
                bankContainer.setItem(slot, ItemStack.EMPTY);
            }
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to sell items from custom bank for {}", player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.bank_config_load_failed", exception.getMessage()));
            return true;
        }

        if (totalValue.signum() > 0) {
            PlayerExtensionKt.setCobbleDollars(player, PlayerExtensionKt.getCobbleDollars(player).add(totalValue));
            ShopFeedbackService.onSellSuccess(player, soldItemCount, totalValue);
            TransactionAuditLogger.logSellSuccess(player, shop, soldItemCount, totalValue, soldItems);
        } else {
            ShopFeedbackService.onSellFailure(player, SellFailureReason.NOTHING_SELLABLE);
        }
        bankContainer.setChanged();
        bankMenu.broadcastChanges();
        return true;
    }

    private static ShopDefinition resolveSessionShop(MinecraftServer server, ServerPlayer player, CommandShopSession session) {
        ShopDefinition shop = ShopRegistry.getShop(session.shopId());
        if (shop == null) {
            cleanupPlayer(player.getUUID());
            player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.shop_missing_after_reload", session.shopId()));
            return null;
        }

        ShopRegistry.ShopAccessResult accessResult = ShopRegistry.evaluateAccess(server, shop, player);
        if (accessResult.allowed()) {
            return shop;
        }

        cleanupPlayer(player.getUUID());
        player.closeContainer();
        ShopFeedbackService.onShopDenied(player, accessResult.denialMessage());
        return null;
    }

    private static boolean isViewingSession(ServerPlayer player, CommandShopSession session) {
        return isViewingSessionShop(player, session) || isViewingSessionBank(player, session);
    }

    private static boolean isViewingSessionShop(ServerPlayer player, CommandShopSession session) {
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            return false;
        }
        if (shopMenu.containerId != session.containerId()) {
            return false;
        }

        return matchesSession(shopMenu.getCobbleMerchant(), session);
    }

    private static boolean isViewingSessionBank(ServerPlayer player, CommandShopSession session) {
        if (!(player.containerMenu instanceof BankMenu bankMenu)) {
            return false;
        }
        return matchesSession(bankMenu.getCobbleMerchant(), session);
    }

    private static boolean matchesSession(CobbleDollarsShopHolder holder, CommandShopSession session) {
        return holder != null && session.sessionUuid().equals(holder.getMerchantUUID());
    }

    private static void updateSessionRefreshState(
            MinecraftServer server,
            ServerPlayer player,
            CommandShopSession session,
            ShopDefinition shop,
            ShopDefinition.RuntimeShopData runtimeData,
            long nowMillis,
            long nextRestockAtMillis
    ) {
        long currentTick = server.getTickCount();
        long timeOfDay = Math.floorMod(player.level().getDayTime(), 24000L);
        long nextRefreshTick = Long.MAX_VALUE;

        if (shop.hasPlayerStateConditions()) {
            nextRefreshTick = Math.min(nextRefreshTick, currentTick + 20L);
        }
        nextRefreshTick = scheduleTick(nextRefreshTick, currentTick, shop.nextTimeRefreshDelayTicks(timeOfDay));

        if (shop.hasRestockingOffers()) {
            long scheduledRestockAtMillis = nextRestockAtMillis;
            if (scheduledRestockAtMillis == Long.MAX_VALUE && runtimeData != null) {
                scheduledRestockAtMillis = runtimeData.nextVisibleRestockAtMillis();
            }
            if (scheduledRestockAtMillis != Long.MAX_VALUE) {
                nextRefreshTick = Math.min(nextRefreshTick, currentTick + millisToTicks(scheduledRestockAtMillis - nowMillis));
            }
        }

        if (isViewingSessionBank(player, session)) {
            BankDefinition bankDefinition = ShopRegistry.getBankDefinition(shop.id());
            if (bankDefinition.hasPlayerStateConditions()) {
                nextRefreshTick = Math.min(nextRefreshTick, currentTick + 20L);
            }
            nextRefreshTick = scheduleTick(nextRefreshTick, currentTick, bankDefinition.nextTimeRefreshDelayTicks(timeOfDay));
        }

        session.setNextRefreshTick(nextRefreshTick);
        session.setLastDimensionId(player.level().dimension().location());
    }

    private static long scheduleTick(long currentNextTick, long currentTick, long delayTicks) {
        if (delayTicks == Long.MAX_VALUE) {
            return currentNextTick;
        }
        return Math.min(currentNextTick, currentTick + Math.max(1L, delayTicks));
    }

    private static long millisToTicks(long deltaMillis) {
        if (deltaMillis <= 0L) {
            return 1L;
        }
        return Math.max(1L, (deltaMillis + 49L) / 50L);
    }

    private static boolean shouldRefreshForDimensionChange(ServerPlayer player, CommandShopSession session, ResourceLocation currentDimension) {
        ResourceLocation previousDimension = session.lastDimensionId();
        if (previousDimension == null || currentDimension.equals(previousDimension)) {
            return false;
        }

        ShopDefinition shop = ShopRegistry.getShop(session.shopId());
        if (shop == null || shop.hasDimensionConditions()) {
            return true;
        }

        return isViewingSessionBank(player, session) && ShopRegistry.getBankDefinition(session.shopId()).hasDimensionConditions();
    }

    private static PurchaseExecutionPlan buildPurchaseExecutionPlan(ResolvedShopOffer offerDefinition, Offer runtimeOffer, int amount) {
        ArrayList<DeliveryRequest> deliveries = new ArrayList<>();
        deliveries.add(new DeliveryRequest(runtimeOffer.getItem(), StackCountMath.multiplyToLong(runtimeOffer.getItem().getCount(), amount)));

        ArrayList<ItemStack> bonusItems = new ArrayList<>();
        for (PurchaseBonusDefinition purchaseBonus : offerDefinition.source().purchaseBonuses()) {
            int multiplier = purchaseBonus.multiplierFor(amount);
            if (multiplier <= 0) {
                continue;
            }

            for (RewardStackDefinition reward : purchaseBonus.rewards()) {
                long totalCount = StackCountMath.multiplyToLong(reward.template().getCount(), multiplier);
                if (!StackCountMath.fitsInInt(totalCount)) {
                    return null;
                }
                deliveries.add(new DeliveryRequest(reward.template(), totalCount));
                bonusItems.add(reward.template().copyWithCount(StackCountMath.toIntExact(totalCount, "bonus reward count")));
            }
        }

        return new PurchaseExecutionPlan(List.copyOf(deliveries), List.copyOf(bonusItems));
    }

    private static void sendFullSync(ServerPlayer player, CommandShopSession session, Shop runtimeShop) {
        ShopMenu shopMenu = requireShopMenu(player);
        CobbleDollarsShopHolder holder = ensureHolder(shopMenu);
        holder.setMerchantUUID(session.sessionUuid());
        holder.setShop(runtimeShop);
        shopMenu.setCobbleMerchant(holder);
        shopMenu.setHasMerchant(false);
        shopMenu.setShop(runtimeShop);
        new SyncShopPacket(shopMenu.containerId, runtimeShop, false, session.sessionUuid(), false).sendToPlayer(player);
    }

    private static void refreshSessionShop(ServerPlayer player, CommandShopSession session, Shop runtimeShop) {
        ShopMenu shopMenu = requireShopMenu(player);
        CobbleDollarsShopHolder holder = ensureHolder(shopMenu);
        Shop currentShop = shopMenu.getShop();

        holder.setMerchantUUID(session.sessionUuid());
        holder.setShop(runtimeShop);
        shopMenu.setCobbleMerchant(holder);
        shopMenu.setHasMerchant(false);
        shopMenu.setShop(runtimeShop);

        if (currentShop == null || currentShop.size() != runtimeShop.size()) {
            new SyncShopPacket(shopMenu.containerId, runtimeShop, false, session.sessionUuid(), false).sendToPlayer(player);
            return;
        }

        for (int categoryIndex = 0; categoryIndex < runtimeShop.size(); categoryIndex++) {
            Category currentCategory = currentShop.get(categoryIndex);
            Category runtimeCategory = runtimeShop.get(categoryIndex);
            if (currentCategory.getOffers().size() != runtimeCategory.getOffers().size()) {
                new SyncShopPacket(shopMenu.containerId, runtimeShop, false, session.sessionUuid(), false).sendToPlayer(player);
                return;
            }

            for (int offerIndex = 0; offerIndex < runtimeCategory.getOffers().size(); offerIndex++) {
                Offer currentOffer = currentCategory.getOffers().get(offerIndex);
                Offer runtimeOffer = runtimeCategory.getOffers().get(offerIndex);
                if (!currentOffer.equalsWithoutStock(runtimeOffer)) {
                    new SyncShopPacket(shopMenu.containerId, runtimeShop, false, session.sessionUuid(), false).sendToPlayer(player);
                    return;
                }
                if (currentOffer.getStock() != runtimeOffer.getStock()) {
                    sendStockUpdate(player, session, categoryIndex, offerIndex, runtimeOffer.getStock());
                }
            }
        }
    }

    private static void sendStockUpdate(ServerPlayer player, CommandShopSession session, int categoryIndex, int offerIndex, int stock) {
        new UpdateStockPacket(categoryIndex, offerIndex, stock, session.sessionUuid()).sendToPlayer(player);
    }

    private static void resyncRejectedOfferState(
            ServerPlayer player,
            CommandShopSession session,
            ShopDefinition.RuntimeShopData runtimeData,
            int categoryIndex,
            int offerIndex
    ) {
        Offer runtimeOffer = runtimeData.getRuntimeOffer(categoryIndex, offerIndex);
        if (runtimeOffer == null) {
            sendFullSync(player, session, runtimeData.shop());
        } else {
            sendStockUpdate(player, session, categoryIndex, offerIndex, runtimeOffer.getStock());
        }
        syncClientShopUiState(player, session, runtimeData);
    }

    private static void syncClientBankConfig(ServerPlayer player, Shop runtimeShop, BankDefinition.RuntimeBankData runtimeBankData) {
        try {
            new SyncShopConfigPacket(runtimeShop, runtimeBankData.bank()).sendToPlayer(player);
            ClientUiSync.sendBankUiState(player, runtimeBankData);
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to sync custom bank config for {}", player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.bank_config_load_failed", exception.getMessage()));
        }
    }

    private static long syncClientShopUiState(ServerPlayer player, CommandShopSession session, ShopDefinition.RuntimeShopData runtimeData) {
        return syncClientShopUiState(player, session, runtimeData, null, 0L, null);
    }

    private static long syncClientShopUiState(
            ServerPlayer player,
            CommandShopSession session,
            ShopDefinition.RuntimeShopData runtimeData,
            PlayerShopStockData stockData,
            long nowMillis
    ) {
        return syncClientShopUiState(player, session, runtimeData, stockData, nowMillis, null);
    }

    private static long syncClientShopUiState(
            ServerPlayer player,
            CommandShopSession session,
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

    private static ShopMenu requireShopMenu(ServerPlayer player) {
        if (player.containerMenu instanceof ShopMenu shopMenu) {
            return shopMenu;
        }
        throw new IllegalStateException("Player is no longer viewing a CobbleDollars shop.");
    }

    private static BankMenu requireBankMenu(ServerPlayer player) {
        if (player.containerMenu instanceof BankMenu bankMenu) {
            return bankMenu;
        }
        throw new IllegalStateException("Player is no longer viewing a CobbleDollars bank.");
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

    private static CommandShopSession getSession(ServerPlayer player, UUID merchantUuid) {
        CommandShopSession session = ACTIVE_SESSIONS.get(player.getUUID());
        if (session == null || !session.sessionUuid().equals(merchantUuid)) {
            return null;
        }
        return session;
    }

    private static CobbleDollarsShopHolder createSessionHolder(UUID merchantUuid, Shop runtimeShop) {
        ClientSideCobbleDollarsShopHolder holder = new ClientSideCobbleDollarsShopHolder();
        holder.setMerchantUUID(merchantUuid);
        holder.setShop(runtimeShop);
        holder.setTradingPlayers(new HashSet<>());
        return holder;
    }

    private static void processPendingShopOpens(MinecraftServer server) {
        if (PENDING_SHOP_OPENS.isEmpty()) {
            return;
        }

        long currentTick = server.getTickCount();
        ArrayList<Map.Entry<UUID, PendingShopOpen>> readyEntries = new ArrayList<>();
        Iterator<Map.Entry<UUID, PendingShopOpen>> iterator = PENDING_SHOP_OPENS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingShopOpen> entry = iterator.next();
            if (entry.getValue().executeAtTick() > currentTick) {
                continue;
            }
            iterator.remove();
            readyEntries.add(entry);
        }

        for (Map.Entry<UUID, PendingShopOpen> entry : readyEntries) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }

            PendingShopOpen pendingOpen = entry.getValue();
            ShopDefinition shop = ShopRegistry.getShop(pendingOpen.shopId());
            if (shop == null) {
                player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.shop_missing_after_reload", pendingOpen.shopId()));
                continue;
            }

            try {
                openShop(player, shop);
            } catch (IllegalStateException exception) {
                if (shouldRetryQueuedOpen(exception) && pendingOpen.retriesRemaining() > 0) {
                    PENDING_SHOP_OPENS.put(player.getUUID(), pendingOpen.retryAt(currentTick + 1L));
                    continue;
                }

                CobbleDollarsCommandShopsMod.LOGGER.warn(
                        "Failed to open queued shop '{}' for {}: {}",
                        pendingOpen.shopId(),
                        player.getGameProfile().getName(),
                        exception.getMessage()
                );
            }
        }
    }

    private static boolean shouldRetryQueuedOpen(IllegalStateException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("did not open a shop menu");
    }

    private record PendingShopOpen(String shopId, long executeAtTick, int retriesRemaining) {
        private PendingShopOpen retryAt(long nextTick) {
            return new PendingShopOpen(shopId, nextTick, retriesRemaining - 1);
        }
    }

    private record PurchaseExecutionPlan(List<DeliveryRequest> deliveries, List<ItemStack> bonusItems) {
    }

    private record DeliveryRequest(ItemStack template, long totalCount) {
        private DeliveryRequest {
            template = template.copy();
        }
    }

    private record InventorySnapshot(List<ItemStack> items, List<ItemStack> armor, List<ItemStack> offhand, int selectedSlot) {
        private static InventorySnapshot capture(ServerPlayer player) {
            return capture(player.getInventory());
        }

        private static InventorySnapshot capture(Inventory inventory) {
            ArrayList<ItemStack> items = new ArrayList<>(inventory.items.size());
            for (ItemStack item : inventory.items) {
                items.add(item.copy());
            }
            ArrayList<ItemStack> armor = new ArrayList<>(inventory.armor.size());
            for (ItemStack item : inventory.armor) {
                armor.add(item.copy());
            }
            ArrayList<ItemStack> offhand = new ArrayList<>(inventory.offhand.size());
            for (ItemStack item : inventory.offhand) {
                offhand.add(item.copy());
            }
            return new InventorySnapshot(List.copyOf(items), List.copyOf(armor), List.copyOf(offhand), inventory.selected);
        }

        private InventorySnapshot planDelivery(List<DeliveryRequest> deliveries) {
            ArrayList<ItemStack> plannedItems = copyStacks(items);
            ArrayList<ItemStack> plannedArmor = copyStacks(armor);
            ArrayList<ItemStack> plannedOffhand = copyStacks(offhand);

            for (DeliveryRequest delivery : deliveries) {
                if (!addDeliveryStack(delivery.template(), delivery.totalCount(), plannedItems, plannedOffhand, selectedSlot)) {
                    return null;
                }
            }

            return new InventorySnapshot(
                    List.copyOf(plannedItems),
                    List.copyOf(plannedArmor),
                    List.copyOf(plannedOffhand),
                    selectedSlot
            );
        }

        private void apply(Inventory inventory) {
            inventory.selected = selectedSlot;
            for (int index = 0; index < items.size(); index++) {
                inventory.items.set(index, items.get(index).copy());
            }
            for (int index = 0; index < armor.size(); index++) {
                inventory.armor.set(index, armor.get(index).copy());
            }
            for (int index = 0; index < offhand.size(); index++) {
                inventory.offhand.set(index, offhand.get(index).copy());
            }
        }

        private static ArrayList<ItemStack> copyStacks(List<ItemStack> stacks) {
            ArrayList<ItemStack> copies = new ArrayList<>(stacks.size());
            for (ItemStack stack : stacks) {
                copies.add(stack.copy());
            }
            return copies;
        }

        private static boolean addDeliveryStack(ItemStack template, long totalCount, List<ItemStack> items, List<ItemStack> offhand, int selectedSlot) {
            if (template.isEmpty() || totalCount <= 0L) {
                return true;
            }

            long remaining = totalCount;
            while (remaining > 0L) {
                long nextRemaining = addResourceAuto(items, offhand, selectedSlot, template, remaining);
                if (nextRemaining >= remaining) {
                    return false;
                }
                remaining = nextRemaining;
            }
            return true;
        }

        private static long addResourceAuto(List<ItemStack> items, List<ItemStack> offhand, int selectedSlot, ItemStack template, long remainingCount) {
            int slot = getSlotWithRemainingSpace(items, offhand, selectedSlot, template);
            if (slot == -1) {
                slot = getFreeSlot(items);
            }
            if (slot == -1) {
                return remainingCount;
            }
            return addResource(items, offhand, slot, template, remainingCount);
        }

        private static long addResource(List<ItemStack> items, List<ItemStack> offhand, int slot, ItemStack template, long remainingCount) {
            ItemStack currentStack = slot == 40 ? offhand.get(0) : items.get(slot);
            if (currentStack.isEmpty()) {
                currentStack = template.copyWithCount(0);
                if (slot == 40) {
                    offhand.set(0, currentStack);
                } else {
                    items.set(slot, currentStack);
                }
            }

            int spaceLeft = currentStack.getMaxStackSize() - currentStack.getCount();
            if (spaceLeft <= 0) {
                return remainingCount;
            }

            int movedCount = (int) Math.min(remainingCount, spaceLeft);
            currentStack.grow(movedCount);
            return remainingCount - movedCount;
        }

        private static int getSlotWithRemainingSpace(List<ItemStack> items, List<ItemStack> offhand, int selectedSlot, ItemStack stack) {
            if (selectedSlot >= 0 && selectedSlot < items.size() && hasRemainingSpaceForItem(items.get(selectedSlot), stack)) {
                return selectedSlot;
            }
            if (!offhand.isEmpty() && hasRemainingSpaceForItem(offhand.get(0), stack)) {
                return 40;
            }
            for (int slot = 0; slot < items.size(); slot++) {
                if (hasRemainingSpaceForItem(items.get(slot), stack)) {
                    return slot;
                }
            }
            return -1;
        }

        private static int getFreeSlot(List<ItemStack> items) {
            for (int slot = 0; slot < items.size(); slot++) {
                if (items.get(slot).isEmpty()) {
                    return slot;
                }
            }
            return -1;
        }

        private static boolean hasRemainingSpaceForItem(ItemStack currentStack, ItemStack incomingStack) {
            return !currentStack.isEmpty()
                    && ItemStack.isSameItemSameComponents(currentStack, incomingStack)
                    && currentStack.isStackable()
                    && currentStack.getCount() < currentStack.getMaxStackSize();
        }
    }

    private static final class CommandShopSession {
        private final String shopId;
        private final UUID sessionUuid;
        private final int containerId;
        private long nextRefreshTick;
        private ResourceLocation lastDimensionId;

        private CommandShopSession(String shopId, UUID sessionUuid, int containerId) {
            this.shopId = shopId;
            this.sessionUuid = sessionUuid;
            this.containerId = containerId;
            this.nextRefreshTick = Long.MAX_VALUE;
            this.lastDimensionId = null;
        }

        private String shopId() {
            return shopId;
        }

        private UUID sessionUuid() {
            return sessionUuid;
        }

        private int containerId() {
            return containerId;
        }

        private long nextRefreshTick() {
            return nextRefreshTick;
        }

        private void setNextRefreshTick(long nextRefreshTick) {
            this.nextRefreshTick = nextRefreshTick;
        }

        private ResourceLocation lastDimensionId() {
            return lastDimensionId;
        }

        private void setLastDimensionId(ResourceLocation lastDimensionId) {
            this.lastDimensionId = lastDimensionId;
        }
    }

    private static final class ShopUiSyncMetrics {
        private long buildOfferStatesNanos;
        private long sendPayloadNanos;
    }

    private static final class RefreshMetrics {
        private long totalNanos;
        private long resolveSessionShopNanos;
        private long createRuntimeDataNanos;
        private long createRuntimeDataContextNanos;
        private long createRuntimeDataCandidateSelectionNanos;
        private long createRuntimeDataMaterializationNanos;
        private long refreshSessionShopNanos;
        private long syncClientShopUiStateNanos;
        private long syncClientShopUiStateBuildNanos;
        private long syncClientShopUiStateSendNanos;
        private long createRuntimeBankDataNanos;
        private long syncClientBankConfigNanos;
        private long updateSessionRefreshStateNanos;

        private RefreshBreakdown toBreakdown() {
            return new RefreshBreakdown(
                    totalNanos,
                    resolveSessionShopNanos,
                    createRuntimeDataNanos,
                    createRuntimeDataContextNanos,
                    createRuntimeDataCandidateSelectionNanos,
                    createRuntimeDataMaterializationNanos,
                    refreshSessionShopNanos,
                    syncClientShopUiStateNanos,
                    syncClientShopUiStateBuildNanos,
                    syncClientShopUiStateSendNanos,
                    createRuntimeBankDataNanos,
                    syncClientBankConfigNanos,
                    updateSessionRefreshStateNanos
            );
        }
    }

    public record RefreshBreakdown(
            long totalNanos,
            long resolveSessionShopNanos,
            long createRuntimeDataNanos,
            long createRuntimeDataContextNanos,
            long createRuntimeDataCandidateSelectionNanos,
            long createRuntimeDataMaterializationNanos,
            long refreshSessionShopNanos,
            long syncClientShopUiStateNanos,
            long syncClientShopUiStateBuildNanos,
            long syncClientShopUiStateSendNanos,
            long createRuntimeBankDataNanos,
            long syncClientBankConfigNanos,
            long updateSessionRefreshStateNanos
    ) {
    }
}
