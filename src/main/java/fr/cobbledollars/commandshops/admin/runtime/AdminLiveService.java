package fr.cobbledollars.commandshops.admin.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.PlayerShopStockData;
import fr.cobbledollars.commandshops.shop.ResolvedShopOffer;
import fr.cobbledollars.commandshops.shop.RestockRule;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import fr.cobbledollars.commandshops.shop.ShopOfferDefinition;
import fr.cobbledollars.commandshops.shop.ShopRegistry;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.IOUtilities;

/** Live world state: independent of unsaved configuration documents in the admin editor. */
public final class AdminLiveService {
    private AdminLiveService() {
    }

    /** Must be called on the server thread, including for read-only requests. */
    public static JsonObject execute(ServerPlayer admin, String action, String shopId, String playerId,
                                     String offerId, String stockKey, int amount, boolean enabled,
                                     String message, boolean allPlayers) {
        if (!admin.hasPermissions(2)) {
            throw new SecurityException("Administrator permission level 2 is required.");
        }
        MinecraftServer server = admin.getServer();
        if (server == null) {
            throw new IllegalStateException("The player is no longer connected to a server.");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Live administration must run on the server thread.");
        }
        if ("overview".equals(action)) {
            return overview(server);
        }
        if (action == null || !List.of("stock", "visibility", "set_stock", "restock_offer", "restock_shop").contains(action)) {
            throw new IllegalArgumentException("Unknown live administration action '" + action + "'.");
        }
        ShopDefinition shop = requireShop(shopId);
        if ("visibility".equals(action)) {
            if (message != null && message.length() > 4096) {
                throw new IllegalArgumentException("The access denial message is too long (maximum 4096 characters).");
            }
            ShopVisibilityData visibility = ShopVisibilityData.get(server);
            ShopVisibilityData.VisibilityStatus status = enabled
                    ? visibility.enable(shop.id())
                    : visibility.disable(shop.id(), message, admin.getGameProfile().getName(), System.currentTimeMillis());
            // Persist before sending an acknowledgement or closing menus.
            persist(server);
            if (!enabled) {
                CommandShopSessions.closeShopSessions(server, shop.id(), status.denialMessage(shop.id()));
            }
            AuditLogService.logVisibilityChanged(shop.id(), enabled, status.message(),
                    admin.getGameProfile().getName(), admin.getStringUUID());
            return overview(server);
        }

        UUID selectedPlayer = resolvePlayerId(server, admin, playerId);
        long nowMillis = System.currentTimeMillis();
        if ("stock".equals(action)) {
            return stock(server, shop, selectedPlayer, nowMillis, false, 0);
        }

        // Resolve and validate every target and concrete offer before touching any stock.
        List<UUID> targets = allPlayers
                ? server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList()
                : List.of(selectedPlayer);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("There are no online players to update.");
        }
        List<ResolvedShopOffer> offers = resolveOffers(shop, action, offerId, stockKey);
        if ("set_stock".equals(action)) {
            if (offers.size() != 1) {
                throw new IllegalArgumentException("Select one concrete item before setting its stock.");
            }
            if (amount < 0 || amount > offers.getFirst().maxStock()) {
                throw new IllegalArgumentException("Stock must be between 0 and " + offers.getFirst().maxStock() + ".");
            }
        }
        PlayerShopStockData data = PlayerShopStockData.get(server);
        for (UUID target : targets) {
            for (ResolvedShopOffer offer : offers) {
                if ("set_stock".equals(action)) {
                    data.setRemainingStock(target, shop, offer, amount, nowMillis);
                } else {
                    data.restockOffer(target, shop, offer, nowMillis);
                }
            }
        }
        persist(server);
        for (UUID target : targets) {
            ServerPlayer online = server.getPlayerList().getPlayer(target);
            if (online != null) {
                CommandShopSessions.refreshPlayerSession(online);
            }
        }
        return stock(server, shop, selectedPlayer, nowMillis, allPlayers, targets.size());
    }

    private static void persist(MinecraftServer server) {
        server.overworld().getDataStorage().save();
        // NeoForge schedules SavedData writes asynchronously. Complete that queue before
        // acknowledging an administrator's immediate world-state change.
        IOUtilities.waitUntilIOWorkerComplete();
    }

    private static ShopDefinition requireShop(String shopId) {
        if (shopId == null || shopId.isBlank()) {
            throw new IllegalArgumentException("Select a saved shop first.");
        }
        ShopDefinition shop = ShopRegistry.getShop(shopId);
        if (shop == null) {
            throw new IllegalArgumentException("Shop '" + shopId + "' no longer exists. Refresh the shop list.");
        }
        return shop;
    }

    private static List<ResolvedShopOffer> resolveOffers(ShopDefinition shop, String action, String offerId, String stockKey) {
        ArrayList<ResolvedShopOffer> resolved = new ArrayList<>();
        if ("restock_shop".equals(action)) {
            for (ShopOfferDefinition offer : shop.offers()) {
                if (offer.hasFiniteStock()) {
                    resolved.addAll(offer.createResolvedOffers());
                }
            }
        } else {
            if (offerId == null || offerId.isBlank()) {
                throw new IllegalArgumentException("Select a saved offer first.");
            }
            ShopOfferDefinition source = shop.getOfferById(offerId);
            if (source == null) {
                throw new IllegalArgumentException("Offer '" + offerId + "' no longer exists. Refresh stock.");
            }
            if (!source.hasFiniteStock()) {
                throw new IllegalArgumentException("Offer '" + offerId + "' has unlimited stock.");
            }
            for (ResolvedShopOffer offer : source.createResolvedOffers()) {
                if (stockKey == null || stockKey.isBlank() || offer.stockKey().equals(stockKey)) {
                    resolved.add(offer);
                }
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("There is no matching finite stock. Refresh stock before trying again.");
        }
        return List.copyOf(resolved);
    }

    private static UUID resolvePlayerId(MinecraftServer server, ServerPlayer admin, String input) {
        if (input == null || input.isBlank()) {
            return admin.getUUID();
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(input);
        if (online != null) {
            return online.getUUID();
        }
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Select an online player or enter a player's UUID.");
        }
    }

    private static JsonObject overview(MinecraftServer server) {
        JsonObject result = new JsonObject();
        JsonArray shops = new JsonArray();
        ShopVisibilityData visibility = ShopVisibilityData.get(server);
        for (String shopId : ShopRegistry.listShopIds()) {
            ShopVisibilityData.VisibilityStatus status = visibility.status(shopId);
            JsonObject shop = new JsonObject();
            shop.addProperty("id", shopId);
            shop.addProperty("enabled", status.enabled());
            shop.addProperty("message", status.message() == null ? "" : status.message());
            shop.addProperty("changedBy", status.changedBy() == null ? "" : status.changedBy());
            shop.addProperty("changedAt", status.changedAtMillis());
            shops.add(shop);
        }
        result.add("shops", shops);
        Map<UUID, JsonObject> playersById = new LinkedHashMap<>();
        server.getPlayerList().getPlayers().stream()
                .sorted(Comparator.comparing(player -> player.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER))
                .forEach(player -> playersById.put(player.getUUID(), playerEntry(server, player.getUUID())));
        PlayerShopStockData.get(server).knownPlayerIds().stream().sorted()
                .forEach(id -> playersById.computeIfAbsent(id, ignored -> playerEntry(server, id)));
        JsonArray players = new JsonArray();
        playersById.values().forEach(players::add);
        result.add("players", players);
        return result;
    }

    private static JsonObject playerEntry(MinecraftServer server, UUID id) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id.toString());
        entry.addProperty("name", playerName(server, id));
        entry.addProperty("online", server.getPlayerList().getPlayer(id) != null);
        return entry;
    }

    private static String playerName(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        // UUID cache lookup is local. Do not resolve offline names through a network service.
        if (server.getProfileCache() != null) {
            return server.getProfileCache().get(id).map(profile -> profile.getName()).orElse(id.toString());
        }
        return id.toString();
    }

    private static JsonObject stock(MinecraftServer server, ShopDefinition shop, UUID playerId, long nowMillis,
                                    boolean allPlayers, int affectedPlayers) {
        JsonObject result = new JsonObject();
        result.addProperty("shopId", shop.id());
        result.addProperty("playerId", playerId.toString());
        result.addProperty("playerName", playerName(server, playerId));
        result.addProperty("allPlayers", allPlayers);
        result.addProperty("affectedPlayers", affectedPlayers);
        JsonArray offers = new JsonArray();
        PlayerShopStockData data = PlayerShopStockData.get(server);
        for (ShopOfferDefinition source : shop.offers()) {
            if (!source.hasFiniteStock()) {
                continue;
            }
            for (ResolvedShopOffer offer : source.createResolvedOffers()) {
                int current = data.resolveStock(playerId, shop, offer, nowMillis);
                PlayerShopStockData.RestockPreview preview = data.previewNextRestock(playerId, shop, offer, nowMillis);
                JsonObject entry = new JsonObject();
                entry.addProperty("offerId", offer.sourceId());
                entry.addProperty("stockKey", offer.stockKey());
                entry.addProperty("itemId", BuiltInRegistries.ITEM.getKey(offer.itemStack().getItem()).toString());
                entry.addProperty("itemName", offer.itemStack().getHoverName().getString());
                entry.add("itemNameComponent", com.google.gson.JsonParser.parseString(
                        net.minecraft.network.chat.Component.Serializer.toJson(offer.itemStack().getHoverName(), server.registryAccess())));
                entry.addProperty("count", offer.itemStack().getCount());
                entry.addProperty("current", current);
                entry.addProperty("max", offer.maxStock());
                entry.addProperty("restockType", offer.hasRestockRule() ? offer.restockRule().type() : "");
                entry.addProperty("nextRestockAt", preview.hasNextRestock() ? preview.nextRestockAtMillis() : 0L);
                entry.addProperty("nextRestockAmount", preview.nextRestockAmount());
                if (offer.restockRule() instanceof RestockRule.IntervalRestockRule interval) {
                    entry.addProperty("restockAmount", interval.amount());
                    entry.addProperty("restockEverySeconds", interval.everySeconds());
                } else if (offer.restockRule() instanceof RestockRule.DailyRestockRule daily) {
                    entry.addProperty("restockHour", daily.hour());
                    entry.addProperty("restockMinute", daily.minute());
                    entry.addProperty("restockTimeZone", daily.timeZone());
                }
                offers.add(entry);
            }
        }
        result.add("offers", offers);
        return result;
    }
}
