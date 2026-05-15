package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AuditStatsService {
    private AuditStatsService() {
    }

    public static AuditStatsSnapshot readSnapshot(AuditTimeWindow window) throws IOException {
        return readSnapshot(window, Instant.now());
    }

    static AuditStatsSnapshot readSnapshot(AuditTimeWindow window, Instant now) throws IOException {
        Path logFile = AuditLogService.getLogFile();
        if (!Files.exists(logFile)) {
            return AuditStatsSnapshot.empty(window);
        }

        MutableSnapshot snapshot = new MutableSnapshot(window);
        try (BufferedReader reader = Files.newBufferedReader(logFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    if (!window.includes(readInstant(event, "timestamp"), now)) {
                        continue;
                    }
                    AuditEventType eventType = AuditEventType.fromEventName(readString(event, "type"));
                    if (eventType == null) {
                        continue;
                    }
                    snapshot.accept(eventType, event);
                } catch (RuntimeException exception) {
                    CobbleDollarsCommandShopsMod.LOGGER.warn("Skipping invalid audit log line {} in {}", lineNumber, logFile, exception);
                }
            }
        }
        return snapshot.freeze();
    }

    private static Instant readInstant(JsonObject object, String key) {
        return Instant.parse(readString(object, key));
    }

    private static String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("Missing field '" + key + "'.");
        }
        return element.getAsString();
    }

    private static BigInteger readBigInteger(JsonObject object, String key, BigInteger defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        return new BigInteger(element.getAsString());
    }

    private static long readLong(JsonObject object, String key, long defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        return element.getAsLong();
    }

    private static JsonArray readArray(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return new JsonArray();
        }
        return element.getAsJsonArray();
    }

    private static final class MutableSnapshot {
        private final AuditTimeWindow window;
        private long buySuccessCount;
        private long buyFailureCount;
        private long sellSuccessCount;
        private long sellFailureCount;
        private long visibilityChangeCount;
        private BigInteger totalSpent = BigInteger.ZERO;
        private BigInteger totalEarned = BigInteger.ZERO;
        private final Map<String, MutableShopStats> shops = new HashMap<>();
        private final Map<AuditStatsSnapshot.OfferKey, MutableOfferStats> offers = new HashMap<>();
        private final Map<String, MutablePlayerStats> players = new HashMap<>();
        private final Map<String, MutableItemStats> items = new HashMap<>();

        private MutableSnapshot(AuditTimeWindow window) {
            this.window = window;
        }

        private void accept(AuditEventType eventType, JsonObject event) {
            switch (eventType) {
                case BUY_SUCCESS -> acceptBuySuccess(event);
                case BUY_FAILURE -> acceptBuyFailure(event);
                case SELL_SUCCESS -> acceptSellSuccess(event);
                case SELL_FAILURE -> acceptSellFailure(event);
                case VISIBILITY_CHANGED -> acceptVisibilityChanged(event);
            }
        }

        private void acceptBuySuccess(JsonObject event) {
            buySuccessCount++;
            String shopId = readString(event, "shop_id");
            String offerId = readString(event, "offer_id");
            String playerUuid = readString(event, "player_uuid");
            String playerName = readString(event, "player_name");
            BigInteger amount = readBigInteger(event, "currency_amount", BigInteger.ZERO);
            long bundleCount = readLong(event, "bundle_count", 0L);
            long itemCountTotal = readLong(event, "item_count_total", 0L);

            totalSpent = totalSpent.add(amount);
            shop(shopId).onBuySuccess(amount, itemCountTotal);
            offer(shopId, offerId).onBuySuccess(amount, bundleCount, itemCountTotal);
            player(playerUuid, playerName).onBuySuccess(amount, itemCountTotal);
            accumulateBoughtItems(readArray(event, "item_lines"));
        }

        private void acceptBuyFailure(JsonObject event) {
            buyFailureCount++;
            String shopId = readString(event, "shop_id");
            shop(shopId).buyFailureCount++;

            JsonElement playerUuidElement = event.get("player_uuid");
            JsonElement playerNameElement = event.get("player_name");
            if (playerUuidElement != null && playerNameElement != null) {
                player(playerUuidElement.getAsString(), playerNameElement.getAsString()).buyFailureCount++;
            }
            JsonElement offerIdElement = event.get("offer_id");
            if (offerIdElement != null && !offerIdElement.isJsonNull()) {
                offer(shopId, offerIdElement.getAsString()).buyFailureCount++;
            }
        }

        private void acceptSellSuccess(JsonObject event) {
            sellSuccessCount++;
            String shopId = readString(event, "shop_id");
            String playerUuid = readString(event, "player_uuid");
            String playerName = readString(event, "player_name");
            BigInteger amount = readBigInteger(event, "currency_amount", BigInteger.ZERO);
            long itemCountTotal = readLong(event, "item_count_total", 0L);

            totalEarned = totalEarned.add(amount);
            shop(shopId).onSellSuccess(amount, itemCountTotal);
            player(playerUuid, playerName).onSellSuccess(amount, itemCountTotal);
            accumulateSoldItems(readArray(event, "item_lines"));
        }

        private void acceptSellFailure(JsonObject event) {
            sellFailureCount++;
            String shopId = readString(event, "shop_id");
            shop(shopId).sellFailureCount++;

            JsonElement playerUuidElement = event.get("player_uuid");
            JsonElement playerNameElement = event.get("player_name");
            if (playerUuidElement != null && playerNameElement != null) {
                player(playerUuidElement.getAsString(), playerNameElement.getAsString()).sellFailureCount++;
            }
        }

        private void acceptVisibilityChanged(JsonObject event) {
            visibilityChangeCount++;
            shop(readString(event, "shop_id")).visibilityChangeCount++;
        }

        private void accumulateBoughtItems(JsonArray itemLines) {
            for (JsonElement element : itemLines) {
                JsonObject line = element.getAsJsonObject();
                String itemId = readString(line, "item");
                String itemName = readString(line, "name");
                long count = readLong(line, "count", 0L);
                BigInteger lineTotal = readBigInteger(line, "line_total", BigInteger.ZERO);
                item(itemId, itemName).onBought(count, lineTotal);
            }
        }

        private void accumulateSoldItems(JsonArray itemLines) {
            for (JsonElement element : itemLines) {
                JsonObject line = element.getAsJsonObject();
                String itemId = readString(line, "item");
                String itemName = readString(line, "name");
                long count = readLong(line, "count", 0L);
                BigInteger lineTotal = readBigInteger(line, "line_total", BigInteger.ZERO);
                item(itemId, itemName).onSold(count, lineTotal);
            }
        }

        private MutableShopStats shop(String shopId) {
            return shops.computeIfAbsent(shopId, ignored -> new MutableShopStats(shopId));
        }

        private MutableOfferStats offer(String shopId, String offerId) {
            AuditStatsSnapshot.OfferKey key = new AuditStatsSnapshot.OfferKey(shopId, offerId);
            return offers.computeIfAbsent(key, ignored -> new MutableOfferStats(shopId, offerId));
        }

        private MutablePlayerStats player(String playerUuid, String playerName) {
            MutablePlayerStats stats = players.computeIfAbsent(playerUuid, ignored -> new MutablePlayerStats(playerUuid, playerName));
            stats.updatePlayerName(playerName);
            return stats;
        }

        private MutableItemStats item(String itemId, String itemName) {
            String normalizedItemId = itemId.toLowerCase(Locale.ROOT);
            MutableItemStats stats = items.computeIfAbsent(normalizedItemId, ignored -> new MutableItemStats(normalizedItemId, itemName));
            stats.updateItemName(itemName);
            return stats;
        }

        private AuditStatsSnapshot freeze() {
            Map<String, AuditStatsSnapshot.ShopStats> frozenShops = new HashMap<>();
            for (Map.Entry<String, MutableShopStats> entry : shops.entrySet()) {
                frozenShops.put(entry.getKey(), entry.getValue().freeze());
            }

            Map<AuditStatsSnapshot.OfferKey, AuditStatsSnapshot.OfferStats> frozenOffers = new HashMap<>();
            for (Map.Entry<AuditStatsSnapshot.OfferKey, MutableOfferStats> entry : offers.entrySet()) {
                frozenOffers.put(entry.getKey(), entry.getValue().freeze());
            }

            Map<String, AuditStatsSnapshot.PlayerStats> frozenPlayers = new HashMap<>();
            for (Map.Entry<String, MutablePlayerStats> entry : players.entrySet()) {
                frozenPlayers.put(entry.getKey(), entry.getValue().freeze());
            }

            Map<String, AuditStatsSnapshot.ItemStats> frozenItems = new HashMap<>();
            for (Map.Entry<String, MutableItemStats> entry : items.entrySet()) {
                frozenItems.put(entry.getKey(), entry.getValue().freeze());
            }

            return new AuditStatsSnapshot(
                    window,
                    buySuccessCount,
                    buyFailureCount,
                    sellSuccessCount,
                    sellFailureCount,
                    visibilityChangeCount,
                    totalSpent,
                    totalEarned,
                    frozenShops,
                    frozenOffers,
                    frozenPlayers,
                    frozenItems
            );
        }
    }

    private static final class MutableShopStats {
        private final String shopId;
        private BigInteger spent = BigInteger.ZERO;
        private BigInteger earned = BigInteger.ZERO;
        private long buySuccessCount;
        private long buyFailureCount;
        private long sellSuccessCount;
        private long sellFailureCount;
        private long visibilityChangeCount;
        private long boughtItemCount;
        private long soldItemCount;

        private MutableShopStats(String shopId) {
            this.shopId = shopId;
        }

        private void onBuySuccess(BigInteger amount, long itemCount) {
            spent = spent.add(amount);
            buySuccessCount++;
            boughtItemCount += itemCount;
        }

        private void onSellSuccess(BigInteger amount, long itemCount) {
            earned = earned.add(amount);
            sellSuccessCount++;
            soldItemCount += itemCount;
        }

        private AuditStatsSnapshot.ShopStats freeze() {
            return new AuditStatsSnapshot.ShopStats(shopId, spent, earned, buySuccessCount, buyFailureCount, sellSuccessCount, sellFailureCount, visibilityChangeCount, boughtItemCount, soldItemCount);
        }
    }

    private static final class MutableOfferStats {
        private final String shopId;
        private final String offerId;
        private BigInteger spent = BigInteger.ZERO;
        private long buySuccessCount;
        private long buyFailureCount;
        private long bundleCount;
        private long itemCount;

        private MutableOfferStats(String shopId, String offerId) {
            this.shopId = shopId;
            this.offerId = offerId;
        }

        private void onBuySuccess(BigInteger amount, long bundles, long items) {
            spent = spent.add(amount);
            buySuccessCount++;
            bundleCount += bundles;
            itemCount += items;
        }

        private AuditStatsSnapshot.OfferStats freeze() {
            return new AuditStatsSnapshot.OfferStats(shopId, offerId, spent, buySuccessCount, buyFailureCount, bundleCount, itemCount);
        }
    }

    private static final class MutablePlayerStats {
        private final String playerUuid;
        private String playerName;
        private BigInteger spent = BigInteger.ZERO;
        private BigInteger earned = BigInteger.ZERO;
        private long buySuccessCount;
        private long buyFailureCount;
        private long sellSuccessCount;
        private long sellFailureCount;
        private long boughtItemCount;
        private long soldItemCount;

        private MutablePlayerStats(String playerUuid, String playerName) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }

        private void onBuySuccess(BigInteger amount, long itemCount) {
            spent = spent.add(amount);
            buySuccessCount++;
            boughtItemCount += itemCount;
        }

        private void onSellSuccess(BigInteger amount, long itemCount) {
            earned = earned.add(amount);
            sellSuccessCount++;
            soldItemCount += itemCount;
        }

        private void updatePlayerName(String latestPlayerName) {
            if (latestPlayerName != null && !latestPlayerName.isBlank()) {
                playerName = latestPlayerName;
            }
        }

        private AuditStatsSnapshot.PlayerStats freeze() {
            return new AuditStatsSnapshot.PlayerStats(playerUuid, playerName, spent, earned, buySuccessCount, buyFailureCount, sellSuccessCount, sellFailureCount, boughtItemCount, soldItemCount);
        }
    }

    private static final class MutableItemStats {
        private final String itemId;
        private String itemName;
        private BigInteger spent = BigInteger.ZERO;
        private BigInteger earned = BigInteger.ZERO;
        private long boughtCount;
        private long soldCount;

        private MutableItemStats(String itemId, String itemName) {
            this.itemId = itemId;
            this.itemName = itemName;
        }

        private void onBought(long count, BigInteger amount) {
            spent = spent.add(amount);
            boughtCount += count;
        }

        private void onSold(long count, BigInteger amount) {
            earned = earned.add(amount);
            soldCount += count;
        }

        private void updateItemName(String latestItemName) {
            if (latestItemName != null && !latestItemName.isBlank()) {
                itemName = latestItemName;
            }
        }

        private AuditStatsSnapshot.ItemStats freeze() {
            return new AuditStatsSnapshot.ItemStats(itemId, itemName, spent, earned, boughtCount, soldCount);
        }
    }
}
