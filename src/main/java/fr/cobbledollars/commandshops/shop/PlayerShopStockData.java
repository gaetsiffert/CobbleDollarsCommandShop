package fr.cobbledollars.commandshops.shop;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class PlayerShopStockData extends SavedData {
    private static final String DATA_NAME = "cobbledollarscommandshops_player_stock";
    private static final String PLAYERS_KEY = "players";
    private static final String STOCK_KEY = "stock";
    private static final String INTERVAL_ANCHOR_KEY = "interval_anchor_ms";
    private static final String DAILY_MARKER_KEY = "daily_marker_ms";
    private static final Factory<PlayerShopStockData> FACTORY = new Factory<>(PlayerShopStockData::new, PlayerShopStockData::load);

    private final Map<UUID, Map<String, Map<String, StoredOfferStock>>> playerStocks = new HashMap<>();

    public static PlayerShopStockData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static PlayerShopStockData load(CompoundTag tag, HolderLookup.Provider provider) {
        PlayerShopStockData data = new PlayerShopStockData();
        CompoundTag playersTag = tag.getCompound(PLAYERS_KEY);

        for (String playerKey : playersTag.getAllKeys()) {
            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(playerKey);
            } catch (IllegalArgumentException exception) {
                continue;
            }

            CompoundTag shopsTag = playersTag.getCompound(playerKey);
            Map<String, Map<String, StoredOfferStock>> shops = new HashMap<>();
            for (String shopId : shopsTag.getAllKeys()) {
                CompoundTag offersTag = shopsTag.getCompound(shopId);
                Map<String, StoredOfferStock> offers = new HashMap<>();
                for (String offerId : offersTag.getAllKeys()) {
                    CompoundTag offerTag = offersTag.getCompound(offerId);
                    offers.put(offerId, new StoredOfferStock(
                            offerTag.getInt(STOCK_KEY),
                            offerTag.getLong(INTERVAL_ANCHOR_KEY),
                            offerTag.getLong(DAILY_MARKER_KEY)
                    ));
                }
                shops.put(shopId, offers);
            }

            data.playerStocks.put(playerUuid, shops);
        }

        return data;
    }

    public int resolveStock(UUID playerUuid, ShopDefinition shop, ShopOfferDefinition offer, long nowMillis) {
        if (!offer.hasFiniteStock()) {
            return -1;
        }

        StoredOfferStock state = getOrCreateState(playerUuid, shop, offer, nowMillis);
        if (applyRestock(state, offer, nowMillis)) {
            setDirty();
        }
        return state.stock();
    }

    public void restockShop(UUID playerUuid, ShopDefinition shop, long nowMillis) {
        for (ShopOfferDefinition offer : shop.offers()) {
            if (offer.hasFiniteStock()) {
                restockOffer(playerUuid, shop, offer, nowMillis);
            }
        }
    }

    public void restockOffer(UUID playerUuid, ShopDefinition shop, ShopOfferDefinition offer, long nowMillis) {
        if (!offer.hasFiniteStock()) {
            return;
        }

        StoredOfferStock state = getOrCreateState(playerUuid, shop, offer, nowMillis);
        state.setStock(offer.stock());
        state.setIntervalAnchorMillis(nowMillis);
        state.setDailyMarkerMillis(resolveDailyMarker(offer.restockRule(), nowMillis));
        setDirty();
    }

    public void consumeStock(UUID playerUuid, ShopDefinition shop, ShopOfferDefinition offer, int amount, long nowMillis) {
        if (!offer.hasFiniteStock() || amount <= 0) {
            return;
        }

        StoredOfferStock state = getOrCreateState(playerUuid, shop, offer, nowMillis);
        if (applyRestock(state, offer, nowMillis)) {
            setDirty();
        }

        int nextStock = Math.max(0, state.stock() - amount);
        if (nextStock != state.stock()) {
            state.setStock(nextStock);
            if (offer.restockRule() instanceof RestockRule.IntervalRestockRule && nextStock < offer.stock()) {
                state.setIntervalAnchorMillis(nowMillis);
            }
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, Map<String, Map<String, StoredOfferStock>>> playerEntry : playerStocks.entrySet()) {
            CompoundTag shopsTag = new CompoundTag();
            for (Map.Entry<String, Map<String, StoredOfferStock>> shopEntry : playerEntry.getValue().entrySet()) {
                CompoundTag offersTag = new CompoundTag();
                for (Map.Entry<String, StoredOfferStock> offerEntry : shopEntry.getValue().entrySet()) {
                    StoredOfferStock stock = offerEntry.getValue();
                    CompoundTag offerTag = new CompoundTag();
                    offerTag.putInt(STOCK_KEY, stock.stock());
                    offerTag.putLong(INTERVAL_ANCHOR_KEY, stock.intervalAnchorMillis());
                    offerTag.putLong(DAILY_MARKER_KEY, stock.dailyMarkerMillis());
                    offersTag.put(offerEntry.getKey(), offerTag);
                }
                shopsTag.put(shopEntry.getKey(), offersTag);
            }
            playersTag.put(playerEntry.getKey().toString(), shopsTag);
        }
        tag.put(PLAYERS_KEY, playersTag);
        return tag;
    }

    private StoredOfferStock getOrCreateState(UUID playerUuid, ShopDefinition shop, ShopOfferDefinition offer, long nowMillis) {
        Map<String, Map<String, StoredOfferStock>> shops = playerStocks.computeIfAbsent(playerUuid, ignored -> new HashMap<>());
        Map<String, StoredOfferStock> offers = shops.computeIfAbsent(shop.id(), ignored -> new HashMap<>());
        StoredOfferStock existing = offers.get(offer.id());
        if (existing != null) {
            return existing;
        }

        StoredOfferStock created = new StoredOfferStock(
                offer.stock(),
                nowMillis,
                resolveDailyMarker(offer.restockRule(), nowMillis)
        );
        offers.put(offer.id(), created);
        setDirty();
        return created;
    }

    private boolean applyRestock(StoredOfferStock state, ShopOfferDefinition offer, long nowMillis) {
        RestockRule restockRule = offer.restockRule();
        if (restockRule == null) {
            return false;
        }
        if (restockRule instanceof RestockRule.IntervalRestockRule intervalRule) {
            return applyIntervalRestock(state, offer, intervalRule, nowMillis);
        }
        if (restockRule instanceof RestockRule.DailyRestockRule dailyRule) {
            return applyDailyRestock(state, offer, dailyRule, nowMillis);
        }
        return false;
    }

    private boolean applyIntervalRestock(StoredOfferStock state, ShopOfferDefinition offer, RestockRule.IntervalRestockRule rule, long nowMillis) {
        int maxStock = offer.stock();
        if (state.stock() >= maxStock) {
            return false;
        }

        long intervalMillis = Math.multiplyExact(rule.everySeconds(), 1000L);
        long elapsed = nowMillis - state.intervalAnchorMillis();
        if (elapsed < intervalMillis) {
            return false;
        }

        long steps = elapsed / intervalMillis;
        if (steps <= 0L) {
            return false;
        }

        int added = Math.toIntExact(Math.min((long) Integer.MAX_VALUE, steps * rule.amount()));
        int newStock = Math.min(maxStock, state.stock() + added);
        state.setStock(newStock);
        if (newStock >= maxStock) {
            state.setIntervalAnchorMillis(nowMillis);
        } else {
            state.setIntervalAnchorMillis(state.intervalAnchorMillis() + (steps * intervalMillis));
        }
        return true;
    }

    private boolean applyDailyRestock(StoredOfferStock state, ShopOfferDefinition offer, RestockRule.DailyRestockRule rule, long nowMillis) {
        long currentMarker = computeDailyMarker(rule, nowMillis);
        if (currentMarker <= state.dailyMarkerMillis()) {
            return false;
        }

        state.setDailyMarkerMillis(currentMarker);
        state.setIntervalAnchorMillis(nowMillis);
        if (state.stock() != offer.stock()) {
            state.setStock(offer.stock());
            return true;
        }
        return true;
    }

    private long resolveDailyMarker(RestockRule restockRule, long nowMillis) {
        if (restockRule instanceof RestockRule.DailyRestockRule dailyRule) {
            return computeDailyMarker(dailyRule, nowMillis);
        }
        return 0L;
    }

    private long computeDailyMarker(RestockRule.DailyRestockRule rule, long nowMillis) {
        ZoneId zoneId = ZoneId.of(rule.timeZone());
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zoneId);
        ZonedDateTime marker = now.withHour(rule.hour()).withMinute(rule.minute()).withSecond(0).withNano(0);
        if (now.isBefore(marker)) {
            marker = marker.minusDays(1L);
        }
        return marker.toInstant().toEpochMilli();
    }

    private static final class StoredOfferStock {
        private int stock;
        private long intervalAnchorMillis;
        private long dailyMarkerMillis;

        private StoredOfferStock(int stock, long intervalAnchorMillis, long dailyMarkerMillis) {
            this.stock = stock;
            this.intervalAnchorMillis = intervalAnchorMillis;
            this.dailyMarkerMillis = dailyMarkerMillis;
        }

        private int stock() {
            return stock;
        }

        private void setStock(int stock) {
            this.stock = stock;
        }

        private long intervalAnchorMillis() {
            return intervalAnchorMillis;
        }

        private void setIntervalAnchorMillis(long intervalAnchorMillis) {
            this.intervalAnchorMillis = intervalAnchorMillis;
        }

        private long dailyMarkerMillis() {
            return dailyMarkerMillis;
        }

        private void setDailyMarkerMillis(long dailyMarkerMillis) {
            this.dailyMarkerMillis = dailyMarkerMillis;
        }
    }
}
