package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class CommandShopSessions {
    private static final Map<UUID, CommandShopSession> ACTIVE_SESSIONS = new HashMap<>();

    private CommandShopSessions() {
    }

    public static void openShop(ServerPlayer player, ShopDefinition shop) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("Player is not attached to a server.");
        }
        if (!shop.isAccessibleBy(player)) {
            throw new IllegalStateException("Player does not meet the conditions for shop '" + shop.id() + "'.");
        }

        cleanupPlayer(server, player.getUUID());

        long nowMillis = System.currentTimeMillis();
        PlayerShopStockData stockData = PlayerShopStockData.get(server);
        Shop runtimeShop = shop.createRuntimeShop(stockData, player, nowMillis);

        PlayerExtensionKt.openShop(player);
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            throw new IllegalStateException("CobbleDollars did not open a shop menu for this player.");
        }

        UUID sessionUuid = UUID.randomUUID();
        CommandShopSession session = new CommandShopSession(shop.id(), sessionUuid, shopMenu.containerId);
        ACTIVE_SESSIONS.put(player.getUUID(), session);
        sendFullSync(player, session, runtimeShop);
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
        Shop runtimeShop = shop.createRuntimeShop(stockData, player, nowMillis);
        PlayerExtensionKt.openBank(player, createSessionHolder(session.sessionUuid(), runtimeShop));
        syncClientBankConfig(player, shop.id(), runtimeShop);
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
        if (packet.getHasMerchant()) {
            return false;
        }

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
        Shop currentRuntimeShop = shop.createRuntimeShop(stockData, player, nowMillis);
        refreshSessionShop(player, session, currentRuntimeShop);

        ShopOfferDefinition offerDefinition = shop.getVisibleOffer(player, packet.getCategoryIndex(), packet.getOfferIndex());
        if (offerDefinition == null) {
            return true;
        }

        Offer expectedOffer = getRuntimeOffer(currentRuntimeShop, packet.getCategoryIndex(), packet.getOfferIndex());
        if (expectedOffer == null || !expectedOffer.equalsWithoutStock(packet.getOffer())) {
            sendFullSync(player, session, currentRuntimeShop);
            return true;
        }

        int amount = Math.max(0, packet.getAmount());
        int bundleSize = Math.max(1, expectedOffer.getItem().getCount());
        int maxBundleAmount = PlayerExtensionKt.getMaxAmountObtainable(player, expectedOffer.getItem()) / bundleSize;
        amount = Math.min(amount, Math.max(0, maxBundleAmount));

        int currentStock = expectedOffer.getStock();
        if (currentStock == 0) {
            sendStockUpdate(player, session, packet.getCategoryIndex(), packet.getOfferIndex(), 0);
            return true;
        }
        if (currentStock > 0) {
            amount = Math.min(amount, currentStock);
        }
        if (amount <= 0) {
            return true;
        }

        BigInteger totalPrice = expectedOffer.getPrice().multiply(BigInteger.valueOf(amount));
        BigInteger balance = PlayerExtensionKt.getCobbleDollars(player);
        if (balance.compareTo(totalPrice) < 0) {
            return true;
        }

        if (offerDefinition.hasFiniteStock()) {
            stockData.consumeStock(player.getUUID(), shop, offerDefinition, amount, nowMillis);
        }

        PlayerExtensionKt.setCobbleDollars(player, balance.subtract(totalPrice));
        giveOfferItems(player, expectedOffer.getItem(), amount);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
                ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7F + 1.0F) * 2.0F);
        player.containerMenu.broadcastChanges();

        Shop updatedRuntimeShop = shop.createRuntimeShop(stockData, player, nowMillis);
        refreshSessionShop(player, session, updatedRuntimeShop);
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
        Shop runtimeShop = shop.createRuntimeShop(stockData, player, System.currentTimeMillis());
        if (isViewingSessionShop(player, session)) {
            refreshSessionShop(player, session, runtimeShop);
            return;
        }
        if (isViewingSessionBank(player, session)) {
            syncClientBankConfig(player, shop.id(), runtimeShop);
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
        boolean refreshStocks = server.getTickCount() % 20 == 0;
        Iterator<Map.Entry<UUID, CommandShopSession>> iterator = ACTIVE_SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CommandShopSession> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !isViewingSession(player, entry.getValue())) {
                iterator.remove();
                continue;
            }

            if (refreshStocks) {
                refreshPlayerSession(player);
            }
        }
    }

    public static void cleanupPlayer(MinecraftServer server, UUID playerUuid) {
        ACTIVE_SESSIONS.remove(playerUuid);
    }

    public static void cleanupAll(MinecraftServer server) {
        ACTIVE_SESSIONS.clear();
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
        try {
            var bank = ShopRegistry.getBank(shop.id(), player);
            for (int slot = 0; slot < bankContainer.getContainerSize(); slot++) {
                ItemStack stack = bankContainer.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }

                Offer offer = bank.get(stack);
                if (offer == null) {
                    continue;
                }

                totalValue = totalValue.add(offer.getPrice().multiply(BigInteger.valueOf(stack.getCount())));
                bankContainer.setItem(slot, ItemStack.EMPTY);
            }
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to sell items from custom bank for {}", player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.literal("Failed to load custom bank config: " + exception.getMessage()));
            return true;
        }

        if (totalValue.signum() > 0) {
            PlayerExtensionKt.setCobbleDollars(player, PlayerExtensionKt.getCobbleDollars(player).add(totalValue));
        }
        bankContainer.setChanged();
        bankMenu.broadcastChanges();
        return true;
    }

    private static ShopDefinition resolveSessionShop(MinecraftServer server, ServerPlayer player, CommandShopSession session) {
        ShopDefinition shop = ShopRegistry.getShop(session.shopId());
        if (shop != null) {
            return shop;
        }

        cleanupPlayer(server, player.getUUID());
        player.sendSystemMessage(Component.literal("Shop '" + session.shopId() + "' no longer exists after reload."));
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

    private static void giveOfferItems(ServerPlayer player, ItemStack template, int amount) {
        int remaining = Math.multiplyExact(template.getCount(), amount);
        int maxStackSize = template.getMaxStackSize();
        while (remaining > 0) {
            int stackCount = Math.min(maxStackSize, remaining);
            player.addItem(template.copyWithCount(stackCount));
            remaining -= stackCount;
        }
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

    private static void syncClientBankConfig(ServerPlayer player, String shopId, Shop runtimeShop) {
        try {
            new SyncShopConfigPacket(runtimeShop, ShopRegistry.getBank(shopId, player)).sendToPlayer(player);
        } catch (Exception exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to sync custom bank config for {}", player.getGameProfile().getName(), exception);
            player.sendSystemMessage(Component.literal("Failed to load custom bank config: " + exception.getMessage()));
        }
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

    private record CommandShopSession(String shopId, UUID sessionUuid, int containerId) {
    }
}
