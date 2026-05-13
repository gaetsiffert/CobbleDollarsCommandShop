package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
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
import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class CommandShopSessions {
    private static final Map<UUID, CommandShopSession> ACTIVE_SESSIONS = new HashMap<>();
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

        cleanupPlayer(server, player.getUUID());

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
        syncClientShopUiState(player, session, runtimeData, stockData, nowMillis);
        updateSessionRefreshState(server, player, session, shop);
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
        updateSessionRefreshState(server, player, session, shop);
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
            cleanupPlayer(server, player.getUUID());
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
        refreshSessionShop(player, session, currentRuntimeShop);
        syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);

        ResolvedShopOffer offerDefinition = currentRuntimeData.getResolvedOffer(packet.getCategoryIndex(), packet.getOfferIndex());
        if (offerDefinition == null) {
            sendFullSync(player, session, currentRuntimeShop);
            syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OFFER_UNAVAILABLE);
            return true;
        }

        Offer expectedOffer = currentRuntimeData.getRuntimeOffer(packet.getCategoryIndex(), packet.getOfferIndex());
        if (expectedOffer == null || !expectedOffer.equalsWithoutStock(packet.getOffer())) {
            sendFullSync(player, session, currentRuntimeShop);
            syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OFFER_CHANGED);
            return true;
        }

        int requestedAmount = Math.max(0, packet.getAmount());
        if (requestedAmount <= 0) {
            return true;
        }
        int amount = requestedAmount;

        int currentStock = expectedOffer.getStock();
        if (currentStock == 0) {
            sendStockUpdate(player, session, packet.getCategoryIndex(), packet.getOfferIndex(), 0);
            syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK);
            return true;
        }
        if (currentStock > 0 && amount > currentStock) {
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.OUT_OF_STOCK);
            return true;
        }

        BigInteger totalPrice = expectedOffer.getPrice().multiply(BigInteger.valueOf(amount));
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player);
        if (balance.compareTo(totalPrice) < 0) {
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_MONEY);
            return true;
        }

        List<ItemStack> bonusItems = offerDefinition.source().createBonusRewardStacks(amount);
        ArrayList<ItemStack> deliveries = new ArrayList<>();
        appendDeliveryStacks(deliveries, expectedOffer.getItem(), amount);
        for (ItemStack bonusItem : bonusItems) {
            appendDeliveryStacks(deliveries, bonusItem, 1);
        }
        if (!canFitAllDeliveries(player, deliveries)) {
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_SPACE);
            return true;
        }

        InventorySnapshot inventorySnapshot = InventorySnapshot.capture(player);
        if (!deliverAllItems(player, deliveries)) {
            inventorySnapshot.restore(player);
            player.containerMenu.broadcastChanges();
            ShopFeedbackService.onBuyFailure(player, BuyFailureReason.NOT_ENOUGH_SPACE);
            return true;
        }

        if (offerDefinition.hasFiniteStock()) {
            stockData.consumeStock(player.getUUID(), shop, offerDefinition, amount, nowMillis);
        }

        PlayerExtensionKt.setCobbleDollars(player, balance.subtract(totalPrice));
        player.containerMenu.broadcastChanges();

        int updatedStock = -1;
        if (offerDefinition.hasFiniteStock()) {
            updatedStock = Math.max(0, currentStock - amount);
            expectedOffer.setStock(updatedStock);
            if (updatedStock < amount) {
                sendFullSync(player, session, currentRuntimeData.shop());
            } else {
                sendStockUpdate(player, session, packet.getCategoryIndex(), packet.getOfferIndex(), updatedStock);
            }
        }
        syncClientShopUiState(player, session, currentRuntimeData, stockData, nowMillis);
        ShopFeedbackService.onBuySuccess(player, expectedOffer.getItem(), amount, totalPrice, updatedStock);
        TransactionAuditLogger.logBuySuccess(player, shop, offerDefinition, amount, totalPrice, bonusItems);
        updateSessionRefreshState(server, player, session, shop);
        return true;
    }

    public static void refreshPlayerSession(ServerPlayer player) {
        CommandShopSession session = ACTIVE_SESSIONS.get(player.getUUID());
        MinecraftServer server = player.getServer();
        if (session == null || server == null) {
            return;
        }

        ShopDefinition shop = resolveSessionShop(server, player, session);
        if (shop == null) {
            return;
        }

        PlayerShopStockData stockData = PlayerShopStockData.get(server);
        long nowMillis = System.currentTimeMillis();
        ShopDefinition.RuntimeShopData runtimeData = shop.createRuntimeData(stockData, player, nowMillis);
        Shop runtimeShop = runtimeData.shop();
        if (isViewingSessionShop(player, session)) {
            refreshSessionShop(player, session, runtimeShop);
            syncClientShopUiState(player, session, runtimeData, stockData, nowMillis);
            updateSessionRefreshState(server, player, session, shop);
            return;
        }
        if (isViewingSessionBank(player, session)) {
            syncClientBankConfig(player, runtimeShop, ShopRegistry.getBankDefinition(shop.id()).createRuntimeData(player));
            updateSessionRefreshState(server, player, session, shop);
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
            if (!currentDimension.equals(entry.getValue().lastDimensionId()) || currentTick >= entry.getValue().nextRefreshTick()) {
                refreshPlayerSession(player);
            }
        }
    }

    public static void cleanupPlayer(MinecraftServer server, UUID playerUuid) {
        ACTIVE_SESSIONS.remove(playerUuid);
        PENDING_SHOP_OPENS.remove(playerUuid);
    }

    public static void cleanupAll(MinecraftServer server) {
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
            cleanupPlayer(server, player.getUUID());
            player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.shop_missing_after_reload", session.shopId()));
            return null;
        }

        ShopRegistry.ShopAccessResult accessResult = ShopRegistry.evaluateAccess(server, shop, player);
        if (accessResult.allowed()) {
            return shop;
        }

        cleanupPlayer(server, player.getUUID());
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

    private static void updateSessionRefreshState(MinecraftServer server, ServerPlayer player, CommandShopSession session, ShopDefinition shop) {
        long currentTick = server.getTickCount();
        long nowMillis = System.currentTimeMillis();
        long timeOfDay = Math.floorMod(player.level().getDayTime(), 24000L);
        long nextRefreshTick = Long.MAX_VALUE;

        if (shop.hasPlayerStateConditions()) {
            nextRefreshTick = Math.min(nextRefreshTick, currentTick + 20L);
        }
        nextRefreshTick = scheduleTick(nextRefreshTick, currentTick, shop.nextTimeRefreshDelayTicks(timeOfDay));

        if (shop.hasRestockingOffers()) {
            long nextRestockAtMillis = PlayerShopStockData.get(server).findNextRestockAtMillis(player.getUUID(), shop, nowMillis);
            if (nextRestockAtMillis != Long.MAX_VALUE) {
                nextRefreshTick = Math.min(nextRefreshTick, currentTick + millisToTicks(nextRestockAtMillis - nowMillis));
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

    private static void appendDeliveryStacks(List<ItemStack> deliveries, ItemStack template, int amount) {
        int remaining = Math.multiplyExact(template.getCount(), amount);
        int maxStackSize = template.getMaxStackSize();
        while (remaining > 0) {
            int stackCount = Math.min(maxStackSize, remaining);
            deliveries.add(template.copyWithCount(stackCount));
            remaining -= stackCount;
        }
    }

    private static boolean deliverAllItems(ServerPlayer player, List<ItemStack> deliveries) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }

        for (ItemStack delivery : deliveries) {
            ItemStack remaining = delivery.copy();
            if (!player.getInventory().add(remaining) || !remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean canFitAllDeliveries(ServerPlayer player, List<ItemStack> deliveries) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }

        ArrayList<ItemStack> items = new ArrayList<>(player.getInventory().items.size());
        for (ItemStack item : player.getInventory().items) {
            items.add(item.copy());
        }
        ItemStack offhand = player.getInventory().offhand.get(0).copy();
        int selected = player.getInventory().selected;

        for (ItemStack delivery : deliveries) {
            ItemStack stack = delivery.copy();
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.isDamaged() || !stack.isStackable()) {
                while (!stack.isEmpty()) {
                    int freeSlot = findFreeItemSlot(items);
                    if (freeSlot < 0) {
                        return false;
                    }
                    items.set(freeSlot, stack.copyWithCount(1));
                    stack.shrink(1);
                }
                continue;
            }

            mergeInto(items.get(selected), stack);
            mergeInto(offhand, stack);
            for (ItemStack item : items) {
                if (stack.isEmpty()) {
                    break;
                }
                mergeInto(item, stack);
            }
            while (!stack.isEmpty()) {
                int freeSlot = findFreeItemSlot(items);
                if (freeSlot < 0) {
                    return false;
                }
                int placed = Math.min(stack.getCount(), stack.getMaxStackSize());
                items.set(freeSlot, stack.copyWithCount(placed));
                stack.shrink(placed);
            }
        }
        return true;
    }

    private static void mergeInto(ItemStack destination, ItemStack source) {
        if (destination.isEmpty() || source.isEmpty()) {
            return;
        }
        if (!ItemStack.isSameItemSameComponents(destination, source) || !destination.isStackable()) {
            return;
        }
        int moved = Math.min(source.getCount(), destination.getMaxStackSize() - destination.getCount());
        if (moved <= 0) {
            return;
        }
        destination.grow(moved);
        source.shrink(moved);
    }

    private static int findFreeItemSlot(List<ItemStack> items) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private static Offer getRuntimeOffer(Shop shop, int categoryIndex, int offerIndex) {
        if (categoryIndex < 0 || categoryIndex >= shop.size()) {
            return null;
        }

        Category category = shop.get(categoryIndex);
        if (offerIndex < 0 || offerIndex >= category.getOffers().size()) {
            return null;
        }

        return category.getOffers().get(offerIndex);
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

    private static void syncClientBankConfig(ServerPlayer player, Shop runtimeShop, BankDefinition.RuntimeBankData runtimeBankData) {
        try {
            new SyncShopConfigPacket(runtimeShop, runtimeBankData.bank()).sendToPlayer(player);
            ClientUiSync.sendBankUiState(player, runtimeBankData);
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to sync custom bank config for {}", player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.translatable("cobbledollarscommandshops.system.bank_config_load_failed", exception.getMessage()));
        }
    }

    private static void syncClientShopUiState(
            ServerPlayer player,
            CommandShopSession session,
            ShopDefinition.RuntimeShopData runtimeData,
            PlayerShopStockData stockData,
            long nowMillis
    ) {
        if (runtimeData == null) {
            return;
        }

        ArrayList<ShopUiStatePayload.OfferState> offers = new ArrayList<>();
        ShopDefinition shop = ShopRegistry.getShop(session.shopId());
        if (shop == null) {
            return;
        }

        for (int visibleCategoryIndex = 0; visibleCategoryIndex < runtimeData.categories().size(); visibleCategoryIndex++) {
            ShopDefinition.RuntimeCategory category = runtimeData.categories().get(visibleCategoryIndex);
            for (int visibleOfferIndex = 0; visibleOfferIndex < category.offers().size(); visibleOfferIndex++) {
                ShopDefinition.RuntimeShopOfferEntry entry = category.offers().get(visibleOfferIndex);
                Offer runtimeOffer = entry.runtimeOffer();
                PlayerShopStockData.RestockPreview preview = stockData.previewNextRestock(player.getUUID(), shop, entry.resolvedOffer(), nowMillis);
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

        ClientUiSync.sendShopUiState(player, session.sessionUuid(), offers);
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
        List<PurchaseBonusDefinition> purchaseBonuses = resolvedOffer.source().purchaseBonuses();
        if (purchaseBonuses.isEmpty()) {
            return List.of();
        }

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

    private record InventorySnapshot(List<ItemStack> items, List<ItemStack> armor, List<ItemStack> offhand, int selectedSlot) {
        private static InventorySnapshot capture(ServerPlayer player) {
            ArrayList<ItemStack> items = new ArrayList<>(player.getInventory().items.size());
            for (ItemStack item : player.getInventory().items) {
                items.add(item.copy());
            }
            ArrayList<ItemStack> armor = new ArrayList<>(player.getInventory().armor.size());
            for (ItemStack item : player.getInventory().armor) {
                armor.add(item.copy());
            }
            ArrayList<ItemStack> offhand = new ArrayList<>(player.getInventory().offhand.size());
            for (ItemStack item : player.getInventory().offhand) {
                offhand.add(item.copy());
            }
            return new InventorySnapshot(List.copyOf(items), List.copyOf(armor), List.copyOf(offhand), player.getInventory().selected);
        }

        private void restore(ServerPlayer player) {
            player.getInventory().selected = selectedSlot;
            for (int index = 0; index < items.size(); index++) {
                player.getInventory().items.set(index, items.get(index).copy());
            }
            for (int index = 0; index < armor.size(); index++) {
                player.getInventory().armor.set(index, armor.get(index).copy());
            }
            for (int index = 0; index < offhand.size(); index++) {
                player.getInventory().offhand.set(index, offhand.get(index).copy());
            }
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
}
