package fr.cobbledollars.commandshops.audit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record AuditStatsSnapshot(
        AuditTimeWindow window,
        long buySuccessCount,
        long buyFailureCount,
        long sellSuccessCount,
        long sellFailureCount,
        long visibilityChangeCount,
        BigInteger totalSpent,
        BigInteger totalEarned,
        Map<String, ShopStats> shopsById,
        Map<OfferKey, OfferStats> offersByKey,
        Map<String, PlayerStats> playersByUuid,
        Map<String, ItemStats> itemsById
) {
    public AuditStatsSnapshot {
        totalSpent = totalSpent == null ? BigInteger.ZERO : totalSpent;
        totalEarned = totalEarned == null ? BigInteger.ZERO : totalEarned;
        shopsById = Map.copyOf(shopsById);
        offersByKey = Map.copyOf(offersByKey);
        playersByUuid = Map.copyOf(playersByUuid);
        itemsById = Map.copyOf(itemsById);
    }

    public static AuditStatsSnapshot empty(AuditTimeWindow window) {
        return new AuditStatsSnapshot(window, 0L, 0L, 0L, 0L, 0L, BigInteger.ZERO, BigInteger.ZERO, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public boolean hasData() {
        return buySuccessCount > 0L || buyFailureCount > 0L || sellSuccessCount > 0L || sellFailureCount > 0L || visibilityChangeCount > 0L;
    }

    public BigInteger netFlow() {
        return totalSpent.subtract(totalEarned);
    }

    public ShopStats shop(String shopId) {
        return shopsById.get(shopId);
    }

    public PlayerStats findPlayer(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        PlayerStats byUuid = playersByUuid.get(query);
        if (byUuid != null) {
            return byUuid;
        }
        String normalized = query.toLowerCase(Locale.ROOT);
        for (PlayerStats player : playersByUuid.values()) {
            if (player.playerName().toLowerCase(Locale.ROOT).equals(normalized)) {
                return player;
            }
        }
        return null;
    }

    public ItemStats item(String itemId) {
        if (itemId == null) {
            return null;
        }
        return itemsById.get(itemId.toLowerCase(Locale.ROOT));
    }

    public List<ShopStats> topShops(int limit) {
        return sortAndLimit(shopsById.values(), Comparator
                .comparing(ShopStats::totalVolume)
                .thenComparing(ShopStats::shopId), limit);
    }

    public List<OfferStats> topOffers(int limit) {
        return sortAndLimit(offersByKey.values(), Comparator
                .comparing(OfferStats::spent)
                .thenComparing(OfferStats::shopId)
                .thenComparing(OfferStats::offerId), limit);
    }

    public List<PlayerStats> topPlayers(int limit) {
        return sortAndLimit(playersByUuid.values(), Comparator
                .comparing(PlayerStats::totalVolume)
                .thenComparing(PlayerStats::playerName, String.CASE_INSENSITIVE_ORDER), limit);
    }

    public List<ItemStats> topItems(int limit) {
        return sortAndLimit(itemsById.values(), Comparator
                .comparing(ItemStats::totalVolume)
                .thenComparing(ItemStats::itemId), limit);
    }

    private static <T> List<T> sortAndLimit(Collection<T> values, Comparator<T> comparator, int limit) {
        ArrayList<T> ordered = new ArrayList<>(values);
        ordered.sort(comparator.reversed());
        if (limit < ordered.size()) {
            ordered.subList(limit, ordered.size()).clear();
        }
        return List.copyOf(ordered);
    }

    public record ShopStats(
            String shopId,
            BigInteger spent,
            BigInteger earned,
            long buySuccessCount,
            long buyFailureCount,
            long sellSuccessCount,
            long sellFailureCount,
            long visibilityChangeCount,
            long boughtItemCount,
            long soldItemCount
    ) {
        public BigInteger totalVolume() {
            return spent.add(earned);
        }
    }

    public record OfferKey(String shopId, String offerId) {
    }

    public record OfferStats(
            String shopId,
            String offerId,
            BigInteger spent,
            long buySuccessCount,
            long buyFailureCount,
            long bundleCount,
            long itemCount
    ) {
    }

    public record PlayerStats(
            String playerUuid,
            String playerName,
            BigInteger spent,
            BigInteger earned,
            long buySuccessCount,
            long buyFailureCount,
            long sellSuccessCount,
            long sellFailureCount,
            long boughtItemCount,
            long soldItemCount
    ) {
        public BigInteger totalVolume() {
            return spent.add(earned);
        }
    }

    public record ItemStats(
            String itemId,
            String itemName,
            BigInteger spent,
            BigInteger earned,
            long boughtCount,
            long soldCount
    ) {
        public BigInteger totalVolume() {
            return spent.add(earned);
        }
    }
}
