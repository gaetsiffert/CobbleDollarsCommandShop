package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.StackCountMath;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.feedback.BuyFailureReason;
import fr.cobbledollars.commandshops.feedback.SellFailureReason;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.harmex.cobbledollars.common.network.packets.c2s.BuyPacket;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.entity.ClientSideCobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class CommandShopSessions {
    private static final Map<UUID, CommandShopSessionState> ACTIVE_SESSIONS = new HashMap<>();
    private static final Map<UUID, PendingShopOpen> PENDING_SHOP_OPENS = new HashMap<>();

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
        UUID sessionUuid = UUID.randomUUID();
        CobbleDollarsShopHolder sessionHolder = createSessionHolder(sessionUuid, runtimeData.shop());
        sessionHolder.getTradingPlayers().add(player);

        PlayerExtensionKt.openShop(player, sessionHolder);
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            throw new IllegalStateException("CobbleDollars did not open a shop menu for this player.");
        }

        CommandShopSessionState session = new CommandShopSessionState(shop.id(), sessionUuid, shopMenu.containerId);
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        ShopSessionSyncService.sendFullSync(player, session, runtimeData);
        long nextRestockAtMillis = ShopSessionSyncService.syncClientShopUiState(player, session, runtimeData);
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
        CommandShopSessionState session = getSession(player, merchantUuid);
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
        ShopSessionSyncService.syncClientBankConfig(player, runtimeShop, ShopRegistry.getBankDefinition(shop.id()).createRuntimeData(player));
        session.setLastSnapshot(runtimeData.sessionSnapshot());
        updateSessionRefreshState(server, player, session, shop, runtimeData, nowMillis, Long.MAX_VALUE);
        return true;
    }

    public static boolean reopenCustomShop(ServerPlayer player, UUID merchantUuid) {
        CommandShopSessionState session = getSession(player, merchantUuid);
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
        CommandShopSessionState session = ACTIVE_SESSIONS.get(player.getUUID());
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
        ResolvedShopOffer offerDefinition = currentRuntimeData.getResolvedOffer(packet.getCategoryIndex(), packet.getOfferIndex());
        if (offerDefinition == null) {
            ShopSessionSyncService.sendFullSync(player, session, currentRuntimeData);
            ShopSessionSyncService.syncClientShopUiState(player, session, currentRuntimeData);
            AuditLogService.logBuyFailure(player, shop, null, BuyFailureReason.OFFER_UNAVAILABLE, packet.getAmount(), null, null);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OFFER_UNAVAILABLE);
            return true;
        }

        Offer expectedOffer = currentRuntimeData.getRuntimeOffer(packet.getCategoryIndex(), packet.getOfferIndex());
        if (expectedOffer == null || !expectedOffer.equalsWithoutStock(packet.getOffer())) {
            ShopSessionSyncService.sendFullSync(player, session, currentRuntimeData);
            ShopSessionSyncService.syncClientShopUiState(player, session, currentRuntimeData);
            AuditLogService.logBuyFailure(
                    player,
                    shop,
                    offerDefinition,
                    BuyFailureReason.OFFER_CHANGED,
                    packet.getAmount(),
                    expectedOffer == null ? null : expectedOffer.getStock(),
                    packet.getAmount() > 0 && expectedOffer != null ? expectedOffer.getPrice().multiply(BigInteger.valueOf(packet.getAmount())) : null
            );
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OFFER_CHANGED);
            return true;
        }

        int requestedAmount = packet.getAmount();
        if (requestedAmount <= 0) {
            AuditLogService.logBuyFailure(player, shop, offerDefinition, BuyFailureReason.INVALID_AMOUNT, requestedAmount, null, null);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.INVALID_AMOUNT);
            return true;
        }
        int amount = requestedAmount;

        int currentStock = expectedOffer.getStock();
        if (currentStock == 0) {
            ShopSessionSyncService.resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            AuditLogService.logBuyFailure(
                    player,
                    shop,
                    offerDefinition,
                    BuyFailureReason.OUT_OF_STOCK,
                    amount,
                    currentStock,
                    expectedOffer.getPrice().multiply(BigInteger.valueOf(amount))
            );
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK);
            return true;
        }
        if (currentStock > 0 && amount > currentStock) {
            ShopSessionSyncService.resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            AuditLogService.logBuyFailure(
                    player,
                    shop,
                    offerDefinition,
                    BuyFailureReason.OUT_OF_STOCK,
                    amount,
                    currentStock,
                    expectedOffer.getPrice().multiply(BigInteger.valueOf(amount))
            );
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK);
            return true;
        }

        BigInteger totalPrice = expectedOffer.getPrice().multiply(BigInteger.valueOf(amount));
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player);
        if (balance.compareTo(totalPrice) < 0) {
            ShopSessionSyncService.resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            AuditLogService.logBuyFailure(player, shop, offerDefinition, BuyFailureReason.NOT_ENOUGH_MONEY, amount, currentStock, totalPrice);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_MONEY);
            return true;
        }

        PurchaseExecutionPlan purchasePlan = buildPurchaseExecutionPlan(offerDefinition, expectedOffer, amount);
        if (purchasePlan == null) {
            ShopSessionSyncService.resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            AuditLogService.logBuyFailure(player, shop, offerDefinition, BuyFailureReason.INVALID_AMOUNT, amount, currentStock, totalPrice);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.INVALID_AMOUNT);
            return true;
        }

        List<ItemStack> bonusItems = purchasePlan.bonusItems();
        InventorySnapshot inventorySnapshot = InventorySnapshot.capture(player);
        InventorySnapshot deliveredInventory = inventorySnapshot.planDelivery(purchasePlan.deliveries());
        if (deliveredInventory == null) {
            ShopSessionSyncService.resyncRejectedOfferState(player, session, currentRuntimeData, packet.getCategoryIndex(), packet.getOfferIndex());
            AuditLogService.logBuyFailure(player, shop, offerDefinition, BuyFailureReason.NOT_ENOUGH_SPACE, amount, currentStock, totalPrice);
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
        ShopSessionSyncService.refreshSessionShop(player, session, currentRuntimeData);
        long nextRestockAtMillis = ShopSessionSyncService.syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);
        ShopFeedbackService.onBuySuccess(player, expectedOffer.getItem(), amount, totalPrice, updatedStock);
        AuditLogService.logBuySuccess(
                player,
                shop,
                offerDefinition,
                amount,
                totalPrice,
                bonusItems,
                offerDefinition.hasFiniteStock() ? currentStock : null,
                updatedStock >= 0 ? updatedStock : null
        );
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
        CommandShopSessionState session = ACTIVE_SESSIONS.get(player.getUUID());
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
        ShopRuntimeBuilder.CreateRuntimeMetrics runtimeMetrics =
                metrics == null ? null : new ShopRuntimeBuilder.CreateRuntimeMetrics();
        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(stockData, player, nowMillis, runtimeMetrics);
        if (metrics != null) {
            metrics.createRuntimeDataNanos = System.nanoTime() - phaseStartNanos;
            metrics.createRuntimeDataContextNanos = runtimeMetrics.contextNanos();
            metrics.createRuntimeDataCandidateSelectionNanos = runtimeMetrics.candidateSelectionNanos();
            metrics.createRuntimeDataMaterializationNanos = runtimeMetrics.materializationNanos();
        }
        if (isViewingSessionShop(player, session)) {
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            ShopSessionSyncService.refreshSessionShop(player, session, runtimeData);
            if (metrics != null) {
                metrics.refreshSessionShopNanos = System.nanoTime() - phaseStartNanos;
            }
            ShopSessionSyncService.ShopUiSyncMetrics syncMetrics =
                    metrics == null ? null : new ShopSessionSyncService.ShopUiSyncMetrics();
            phaseStartNanos = metrics == null ? 0L : System.nanoTime();
            long nextRestockAtMillis = ShopSessionSyncService.syncClientShopUiState(player, session, runtimeData, null, 0L, syncMetrics);
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
            ShopSessionSyncService.syncClientBankConfig(player, runtimeData.shop(), runtimeBankData);
            if (metrics != null) {
                metrics.syncClientBankConfigNanos = System.nanoTime() - phaseStartNanos;
            }
            session.setLastSnapshot(runtimeData.sessionSnapshot());
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
        Iterator<Map.Entry<UUID, CommandShopSessionState>> iterator = ACTIVE_SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CommandShopSessionState> entry = iterator.next();
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

        Iterator<Map.Entry<UUID, CommandShopSessionState>> iterator = ACTIVE_SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CommandShopSessionState> entry = iterator.next();
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
        CommandShopSessionState session = ACTIVE_SESSIONS.get(player.getUUID());
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
        ArrayList<AuditLogService.SoldItemLine> soldItems = new ArrayList<>();
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
                soldItems.add(new AuditLogService.SoldItemLine(stack.copy(), offer.getPrice(), lineTotal));
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
            AuditLogService.logSellSuccess(player, shop, soldItemCount, totalValue, soldItems);
        } else {
            AuditLogService.logSellFailure(player, shop, SellFailureReason.NOTHING_SELLABLE);
            ShopFeedbackService.onSellFailure(player, SellFailureReason.NOTHING_SELLABLE);
        }
        bankContainer.setChanged();
        bankMenu.broadcastChanges();
        return true;
    }

    private static ShopDefinition resolveSessionShop(MinecraftServer server, ServerPlayer player, CommandShopSessionState session) {
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

    private static boolean isViewingSession(ServerPlayer player, CommandShopSessionState session) {
        return isViewingSessionShop(player, session) || isViewingSessionBank(player, session);
    }

    private static boolean isViewingSessionShop(ServerPlayer player, CommandShopSessionState session) {
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            return false;
        }
        if (shopMenu.containerId != session.containerId()) {
            return false;
        }

        return matchesSession(shopMenu.getCobbleMerchant(), session);
    }

    private static boolean isViewingSessionBank(ServerPlayer player, CommandShopSessionState session) {
        if (!(player.containerMenu instanceof BankMenu bankMenu)) {
            return false;
        }
        return matchesSession(bankMenu.getCobbleMerchant(), session);
    }

    private static boolean matchesSession(CobbleDollarsShopHolder holder, CommandShopSessionState session) {
        return holder != null && session.sessionUuid().equals(holder.getMerchantUUID());
    }

    private static void updateSessionRefreshState(
            MinecraftServer server,
            ServerPlayer player,
            CommandShopSessionState session,
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

    private static boolean shouldRefreshForDimensionChange(ServerPlayer player, CommandShopSessionState session, ResourceLocation currentDimension) {
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

    private static BankMenu requireBankMenu(ServerPlayer player) {
        if (player.containerMenu instanceof BankMenu bankMenu) {
            return bankMenu;
        }
        throw new IllegalStateException("Player is no longer viewing a CobbleDollars bank.");
    }

    private static CommandShopSessionState getSession(ServerPlayer player, UUID merchantUuid) {
        CommandShopSessionState session = ACTIVE_SESSIONS.get(player.getUUID());
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
