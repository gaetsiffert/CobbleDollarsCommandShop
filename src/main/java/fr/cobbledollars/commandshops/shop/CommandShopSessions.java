package fr.cobbledollars.commandshops.shop;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.entity.CobbleDollarsEntities;
import fr.harmex.cobbledollars.common.world.entity.CobbleMerchant;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import fr.harmex.cobbledollars.common.world.item.trading.CobbleDollarsShopHolder;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public final class CommandShopSessions {
    private static final Map<UUID, CommandShopSession> ACTIVE_SESSIONS = new HashMap<>();
    private static final String SESSION_TAG = CobbleDollarsCommandShopsMod.MODID + ":session";

    private CommandShopSessions() {
    }

    public static void openShop(ServerPlayer player, Shop shop) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("Player is not attached to a server.");
        }

        cleanupPlayer(server, player.getUUID());

        ServerLevel level = player.serverLevel();
        CobbleMerchant merchant = new CobbleMerchant(CobbleDollarsEntities.COBBLE_MERCHANT, level);
        double y = Math.max(level.getMinBuildHeight() + 1.0D, player.getY() - 64.0D);

        merchant.moveTo(player.getX(), y, player.getZ(), 0.0F, 0.0F);
        merchant.setNoAi(true);
        merchant.setNoGravity(true);
        merchant.setInvulnerable(true);
        merchant.setSilent(true);
        merchant.setInvisible(true);
        merchant.setMerchantUUID(merchant.getUUID());
        merchant.setShop(shop.copy());
        merchant.addTag(SESSION_TAG);

        if (!level.addFreshEntity(merchant)) {
            throw new IllegalStateException("Failed to create the CobbleDollars merchant for this shop.");
        }

        ACTIVE_SESSIONS.put(player.getUUID(), new CommandShopSession(level.dimension(), merchant.getUUID()));
        PlayerExtensionKt.openShop(player, merchant);
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, CommandShopSession>> iterator = ACTIVE_SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, CommandShopSession> entry = iterator.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && isViewingSession(player, entry.getValue())) {
                continue;
            }

            cleanupSession(server, entry.getValue());
            iterator.remove();
        }
    }

    public static void cleanupPlayer(MinecraftServer server, UUID playerUuid) {
        CommandShopSession session = ACTIVE_SESSIONS.remove(playerUuid);
        if (session != null) {
            cleanupSession(server, session);
        }
    }

    public static void cleanupAll(MinecraftServer server) {
        for (CommandShopSession session : ACTIVE_SESSIONS.values()) {
            cleanupSession(server, session);
        }
        ACTIVE_SESSIONS.clear();
    }

    private static boolean isViewingSession(ServerPlayer player, CommandShopSession session) {
        if (!(player.containerMenu instanceof ShopMenu shopMenu)) {
            return false;
        }

        CobbleDollarsShopHolder cobbleMerchant = shopMenu.getCobbleMerchant();
        return cobbleMerchant != null && session.merchantUuid().equals(cobbleMerchant.getMerchantUUID());
    }

    private static void cleanupSession(MinecraftServer server, CommandShopSession session) {
        ServerLevel level = server.getLevel(session.dimension());
        if (level == null) {
            return;
        }

        Entity entity = level.getEntity(session.merchantUuid());
        if (entity != null) {
            entity.remove(Entity.RemovalReason.DISCARDED);
        }
    }

    private record CommandShopSession(ResourceKey<Level> dimension, UUID merchantUuid) {
    }
}
