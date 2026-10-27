package fr.cobbledollars.commandshops.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

public final class AuditStatsService {
    private AuditStatsService() {
    }

    public static AuditStatsSnapshot readSnapshot(AuditTimeWindow window) throws IOException {
        return readSnapshot(window, Instant.now());
    }

    static AuditStatsSnapshot readSnapshot(AuditTimeWindow window, Instant now) throws IOException {
        List<Path> logFiles = AuditLogService.listReadableLogFiles();
        if (logFiles.isEmpty()) {
            return AuditStatsSnapshot.empty(window);
        }

        Accumulator snapshot = new Accumulator(window);
        for (Path logFile : logFiles) {
            try (BufferedReader reader = openReader(logFile)) {
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
            } catch (IOException exception) {
                CobbleDollarsCommandShopsMod.LOGGER.warn("Skipping unreadable audit log file {}", logFile, exception);
            }
        }
        return snapshot.freeze();
    }

    private static BufferedReader openReader(Path logFile) throws IOException {
        if (logFile.getFileName().toString().endsWith(".zip")) {
            InputStream inputStream = Files.newInputStream(logFile);
            try {
                ZipInputStream zipInputStream = new ZipInputStream(inputStream, StandardCharsets.UTF_8);
                if (zipInputStream.getNextEntry() == null) {
                    zipInputStream.close();
                    throw new IOException("Audit archive contains no entry: " + logFile);
                }
                return new BufferedReader(new InputStreamReader(zipInputStream, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                inputStream.close();
                throw exception;
            }
        }
        return Files.newBufferedReader(logFile);
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

    private static String readOptionalString(JsonObject object, String key, String defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
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

    /** Validate before accumulation so a corrupt record cannot partially change totals. */
    static void validateEvent(AuditEventType type, JsonObject event) {
        if (isEconomyEvent(type)) {
            validateEconomyEvent(type, event);
            return;
        }
        if (type != AuditEventType.CONFIG_CHANGED) readString(event, "shop_id");
        if (type == AuditEventType.BUY_SUCCESS || type == AuditEventType.SELL_SUCCESS) {
            readString(event, "player_uuid");
            readString(event, "player_name");
            readBigInteger(event, "currency_amount", BigInteger.ZERO);
            readLong(event, "item_count_total", 0L);
            if (type == AuditEventType.BUY_SUCCESS) {
                readString(event, "offer_id");
                readLong(event, "bundle_count", 0L);
            }
            for (JsonElement element : readArray(event, "item_lines")) {
                JsonObject line = element.getAsJsonObject();
                readString(line, "item");
                readOptionalString(line, "name", "");
                readLong(line, "count", 0L);
                readBigInteger(line, "line_total", BigInteger.ZERO);
            }
        } else if (type == AuditEventType.BUY_FAILURE || type == AuditEventType.SELL_FAILURE) {
            if (event.has("player_uuid") && event.has("player_name")) {
                readString(event, "player_uuid");
                readString(event, "player_name");
            }
            if (event.has("offer_id") && !event.get("offer_id").isJsonNull()) readString(event, "offer_id");
        } else if (type == AuditEventType.STOCK_CHANGED) {
            readString(event, "actor_name"); readString(event, "actor_uuid");
            readString(event, "player_uuid"); readString(event, "player_name");
            readString(event, "offer_id"); readString(event, "stock_key");
            readString(event, "operation_id"); readString(event, "action");
            readLong(event, "stock_before", 0L); readLong(event, "stock_after", 0L);
        } else if (type == AuditEventType.CONFIG_CHANGED) {
            readString(event, "actor_name"); readString(event, "operation_id");
            String phase = readString(event, "phase");
            if (!Set.of("prepared", "applied", "rolled_back", "rollback_failed", "failed", "activation_failed").contains(phase))
                throw new IllegalArgumentException("Unknown configuration audit phase.");
        }
    }

    private static boolean isEconomyEvent(AuditEventType type) {
        return switch (type) {
            case PLAYER_TRANSFER, BALANCE_ADJUSTED, BATTLE_REWARD, NATIVE_BUY_SUCCESS, NATIVE_SELL_SUCCESS -> true;
            default -> false;
        };
    }

    private static BigInteger requiredMoney(JsonObject event, String field) {
        return new BigInteger(readString(event, field));
    }

    private static void validateEconomyEvent(AuditEventType type, JsonObject event) {
        readString(event, "operation_id");
        readString(event, "player_uuid");
        readString(event, "player_name");
        BigInteger before = requiredMoney(event, "balance_before");
        BigInteger after = requiredMoney(event, "balance_after");
        BigInteger amount = requiredMoney(event, "currency_amount");
        BigInteger delta = requiredMoney(event, "currency_delta");
        if (amount.signum() <= 0 || !after.subtract(before).equals(delta) || !delta.abs().equals(amount))
            throw new IllegalArgumentException("Inconsistent audit balance change.");
        switch (type) {
            case PLAYER_TRANSFER -> {
                String recipient = readString(event, "recipient_uuid");
                readString(event, "recipient_name");
                BigInteger received = requiredMoney(event, "recipient_balance_after")
                        .subtract(requiredMoney(event, "recipient_balance_before"));
                if (recipient.equals(readString(event, "player_uuid")) || !delta.equals(amount.negate()) || !received.equals(amount))
                    throw new IllegalArgumentException("Inconsistent audit transfer.");
            }
            case BALANCE_ADJUSTED -> {
                readString(event, "actor_name");
                readOptionalString(event, "actor_uuid", "");
                String action = readString(event, "action");
                if (!Set.of("give", "remove", "set").contains(action) || requiredMoney(event, "requested_amount").signum() < 0)
                    throw new IllegalArgumentException("Invalid audit balance adjustment.");
            }
            case NATIVE_BUY_SUCCESS -> {
                if (delta.signum() >= 0) throw new IllegalArgumentException("Invalid native purchase balance change.");
            }
            case BATTLE_REWARD, NATIVE_SELL_SUCCESS -> {
                if (delta.signum() <= 0) throw new IllegalArgumentException("Invalid audit reward balance change.");
            }
            default -> throw new IllegalArgumentException("Not an economy event.");
        }
        if (type == AuditEventType.NATIVE_BUY_SUCCESS || type == AuditEventType.NATIVE_SELL_SUCCESS) {
            for (JsonElement element : readArray(event, "item_lines")) {
                JsonObject line = element.getAsJsonObject();
                readString(line, "item");
                readOptionalString(line, "name", "");
                readLong(line, "count", 0L);
            }
        }
    }

    static final class Accumulator {
        private final AuditTimeWindow window;
        private long buySuccessCount;
        private long buyFailureCount;
        private long sellSuccessCount;
        private long sellFailureCount;
        private long visibilityChangeCount;
        private long stockChangeCount;
        private long configChangeCount;
        private BigInteger totalSpent = BigInteger.ZERO;
        private BigInteger totalEarned = BigInteger.ZERO;
        private long transferCount;
        private long balanceAdjustmentCount;
        private long battleRewardCount;
        private long nativeBuySuccessCount;
        private long nativeSellSuccessCount;
        private BigInteger transferred = BigInteger.ZERO;
        private BigInteger adminAdded = BigInteger.ZERO;
        private BigInteger adminRemoved = BigInteger.ZERO;
        private BigInteger battleRewards = BigInteger.ZERO;
        private BigInteger nativeSpent = BigInteger.ZERO;
        private BigInteger nativeEarned = BigInteger.ZERO;
        private final Map<String, MutableShopStats> shops = new HashMap<>();
        private final Map<AuditStatsSnapshot.OfferKey, MutableOfferStats> offers = new HashMap<>();
        private final Map<String, MutablePlayerStats> players = new HashMap<>();
        private final Map<String, MutableItemStats> items = new HashMap<>();

        Accumulator(AuditTimeWindow window) {
            this.window = window;
        }

        int entityCount() { return shops.size() + offers.size() + players.size() + items.size(); }

        void accept(AuditEventType eventType, JsonObject event) {
            validateEvent(eventType, event);
            switch (eventType) {
                case BUY_SUCCESS -> acceptBuySuccess(event);
                case BUY_FAILURE -> acceptBuyFailure(event);
                case SELL_SUCCESS -> acceptSellSuccess(event);
                case SELL_FAILURE -> acceptSellFailure(event);
                case VISIBILITY_CHANGED -> acceptVisibilityChanged(event);
                case STOCK_CHANGED -> stockChangeCount++;
                case CONFIG_CHANGED -> {
                    if ("applied".equals(readString(event, "phase"))) configChangeCount++;
                }
                case PLAYER_TRANSFER, BALANCE_ADJUSTED, BATTLE_REWARD, NATIVE_BUY_SUCCESS, NATIVE_SELL_SUCCESS -> acceptEconomyEvent(eventType, event);
            }
        }

        private void acceptEconomyEvent(AuditEventType type, JsonObject event) {
            MutablePlayerStats subject = player(readString(event, "player_uuid"), readString(event, "player_name"));
            BigInteger amount = requiredMoney(event, "currency_amount");
            switch (type) {
                case PLAYER_TRANSFER -> {
                    transferCount++;
                    transferred = transferred.add(amount);
                    subject.transferSentCount++;
                    subject.transferSent = subject.transferSent.add(amount);
                    MutablePlayerStats recipient = player(readString(event, "recipient_uuid"), readString(event, "recipient_name"));
                    recipient.transferReceivedCount++;
                    recipient.transferReceived = recipient.transferReceived.add(amount);
                }
                case BALANCE_ADJUSTED -> {
                    balanceAdjustmentCount++;
                    subject.balanceAdjustmentCount++;
                    if (requiredMoney(event, "currency_delta").signum() > 0) {
                        adminAdded = adminAdded.add(amount);
                        subject.adminAdded = subject.adminAdded.add(amount);
                    } else {
                        adminRemoved = adminRemoved.add(amount);
                        subject.adminRemoved = subject.adminRemoved.add(amount);
                    }
                }
                case BATTLE_REWARD -> {
                    battleRewardCount++;
                    battleRewards = battleRewards.add(amount);
                    subject.battleRewardCount++;
                    subject.battleRewards = subject.battleRewards.add(amount);
                }
                case NATIVE_BUY_SUCCESS -> {
                    nativeBuySuccessCount++;
                    nativeSpent = nativeSpent.add(amount);
                    subject.nativeBuySuccessCount++;
                    subject.nativeSpent = subject.nativeSpent.add(amount);
                }
                case NATIVE_SELL_SUCCESS -> {
                    nativeSellSuccessCount++;
                    nativeEarned = nativeEarned.add(amount);
                    subject.nativeSellSuccessCount++;
                    subject.nativeEarned = subject.nativeEarned.add(amount);
                }
                default -> throw new IllegalArgumentException("Not an economy event.");
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
                String itemName = readOptionalString(line, "name", itemId);
                long count = readLong(line, "count", 0L);
                BigInteger lineTotal = readBigInteger(line, "line_total", BigInteger.ZERO);
                item(itemId, itemName).onBought(count, lineTotal);
            }
        }

        private void accumulateSoldItems(JsonArray itemLines) {
            for (JsonElement element : itemLines) {
                JsonObject line = element.getAsJsonObject();
                String itemId = readString(line, "item");
                String itemName = readOptionalString(line, "name", itemId);
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

        AuditStatsSnapshot freeze() {
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
                    stockChangeCount,
                    configChangeCount,
                    totalSpent,
                    totalEarned,
                    transferCount,
                    balanceAdjustmentCount,
                    battleRewardCount,
                    nativeBuySuccessCount,
                    nativeSellSuccessCount,
                    transferred,
                    adminAdded,
                    adminRemoved,
                    battleRewards,
                    nativeSpent,
                    nativeEarned,
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
        private long transferSentCount;
        private long transferReceivedCount;
        private long balanceAdjustmentCount;
        private long battleRewardCount;
        private long nativeBuySuccessCount;
        private long nativeSellSuccessCount;
        private BigInteger transferSent = BigInteger.ZERO;
        private BigInteger transferReceived = BigInteger.ZERO;
        private BigInteger adminAdded = BigInteger.ZERO;
        private BigInteger adminRemoved = BigInteger.ZERO;
        private BigInteger battleRewards = BigInteger.ZERO;
        private BigInteger nativeSpent = BigInteger.ZERO;
        private BigInteger nativeEarned = BigInteger.ZERO;

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
            return new AuditStatsSnapshot.PlayerStats(playerUuid, playerName, spent, earned, buySuccessCount, buyFailureCount,
                    sellSuccessCount, sellFailureCount, boughtItemCount, soldItemCount,
                    transferSentCount, transferReceivedCount, balanceAdjustmentCount, battleRewardCount,
                    nativeBuySuccessCount, nativeSellSuccessCount, transferSent, transferReceived,
                    adminAdded, adminRemoved, battleRewards, nativeSpent, nativeEarned);
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
