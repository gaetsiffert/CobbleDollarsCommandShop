package fr.cobbledollars.commandshops.shop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.server.level.ServerPlayer;

public final class ShopDefinition {
    private final String id;
    private final List<ShopCategoryDefinition> categories;
    private final ConditionSet conditions;
    private final Path sourceFile;
    private final Map<String, ShopOfferDefinition> offersById;
    private final List<ShopOfferDefinition> offersInOrder;
    private final List<ConditionSet> allConditions;
    private final boolean hasPlayerStateConditions;
    private final boolean hasDimensionConditions;
    private final boolean hasTimeConditions;
    private final boolean hasRestockingOffers;

    public ShopDefinition(String id, List<ShopCategoryDefinition> categories, ConditionSet conditions, Path sourceFile) {
        this.id = id;
        this.categories = List.copyOf(categories);
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
        this.sourceFile = sourceFile;
        this.offersById = buildOfferMap(categories);
        this.offersInOrder = buildOfferList(categories);
        this.allConditions = collectConditions(this.conditions, categories);
        this.hasPlayerStateConditions = allConditions.stream().anyMatch(ConditionSet::hasPlayerStateConditions);
        this.hasDimensionConditions = allConditions.stream().anyMatch(ConditionSet::hasDimensionConditions);
        this.hasTimeConditions = allConditions.stream().anyMatch(ConditionSet::hasTimeConditions);
        this.hasRestockingOffers = offersInOrder.stream().anyMatch(offer -> offer.hasFiniteStock() && offer.hasRestockRule());
    }

    private static Map<String, ShopOfferDefinition> buildOfferMap(List<ShopCategoryDefinition> categories) {
        Map<String, ShopOfferDefinition> offers = new LinkedHashMap<>();
        for (ShopCategoryDefinition category : categories) {
            for (ShopOfferDefinition offer : category.offers()) {
                offers.put(offer.id(), offer);
            }
        }
        return Map.copyOf(offers);
    }

    private static List<ShopOfferDefinition> buildOfferList(List<ShopCategoryDefinition> categories) {
        ArrayList<ShopOfferDefinition> offers = new ArrayList<>();
        for (ShopCategoryDefinition category : categories) {
            offers.addAll(category.offers());
        }
        return List.copyOf(offers);
    }

    private static List<ConditionSet> collectConditions(ConditionSet shopConditions, List<ShopCategoryDefinition> categories) {
        ArrayList<ConditionSet> conditionSets = new ArrayList<>();
        if (!shopConditions.isEmpty()) {
            conditionSets.add(shopConditions);
        }
        for (ShopCategoryDefinition category : categories) {
            if (!category.conditions().isEmpty()) {
                conditionSets.add(category.conditions());
            }
            for (ShopOfferDefinition offer : category.offers()) {
                if (!offer.conditions().isEmpty()) {
                    conditionSets.add(offer.conditions());
                }
            }
        }
        return List.copyOf(conditionSets);
    }

    public String id() {
        return id;
    }

    public List<ShopCategoryDefinition> categories() {
        return categories;
    }

    public ConditionSet conditions() {
        return conditions;
    }

    public Path sourceFile() {
        return sourceFile;
    }

    public List<ShopOfferDefinition> offers() {
        return offersInOrder;
    }

    public List<String> offerIds() {
        return offersById.keySet().stream().sorted().toList();
    }

    public ShopOfferDefinition getOfferById(String offerId) {
        return offersById.get(offerId);
    }

    public ShopOfferDefinition getOffer(int categoryIndex, int offerIndex) {
        if (categoryIndex < 0 || categoryIndex >= categories.size()) {
            return null;
        }

        List<ShopOfferDefinition> offers = categories.get(categoryIndex).offers();
        if (offerIndex < 0 || offerIndex >= offers.size()) {
            return null;
        }

        return offers.get(offerIndex);
    }

    public ShopOfferDefinition getVisibleOffer(ServerPlayer player, int categoryIndex, int offerIndex) {
        if (categoryIndex < 0 || offerIndex < 0 || !conditions.test(player)) {
            return null;
        }

        int visibleCategoryIndex = 0;
        for (ShopCategoryDefinition categoryDefinition : categories) {
            if (!categoryDefinition.conditions().test(player)) {
                continue;
            }

            int visibleOfferIndex = 0;
            for (ShopOfferDefinition offerDefinition : categoryDefinition.offers()) {
                if (!offerDefinition.isVisibleTo(player)) {
                    continue;
                }

                if (visibleCategoryIndex == categoryIndex && visibleOfferIndex == offerIndex) {
                    return offerDefinition;
                }
                visibleOfferIndex++;
            }

            if (visibleOfferIndex > 0) {
                if (visibleCategoryIndex == categoryIndex) {
                    return null;
                }
                visibleCategoryIndex++;
            }
        }
        return null;
    }

    public boolean isAccessibleBy(ServerPlayer player) {
        return conditions.test(player);
    }

    public boolean hasPlayerStateConditions() {
        return hasPlayerStateConditions;
    }

    public boolean hasDimensionConditions() {
        return hasDimensionConditions;
    }

    public boolean hasTimeConditions() {
        return hasTimeConditions;
    }

    public boolean hasRestockingOffers() {
        return hasRestockingOffers;
    }

    public long nextTimeRefreshDelayTicks(long timeOfDay) {
        long nextDelay = Long.MAX_VALUE;
        for (ConditionSet conditionSet : allConditions) {
            nextDelay = Math.min(nextDelay, conditionSet.nextTimeBoundaryDelayTicks(timeOfDay));
        }
        return nextDelay;
    }

    public Shop createRuntimeShop(PlayerShopStockData stockData, ServerPlayer player, long nowMillis) {
        Shop runtimeShop = new Shop();
        if (!conditions.test(player)) {
            return runtimeShop;
        }

        UUID playerUuid = player.getUUID();
        for (ShopCategoryDefinition categoryDefinition : categories) {
            if (!categoryDefinition.conditions().test(player)) {
                continue;
            }

            ArrayList<Offer> runtimeOffers = new ArrayList<>(categoryDefinition.offers().size());
            for (ShopOfferDefinition offerDefinition : categoryDefinition.offers()) {
                if (!offerDefinition.isVisibleTo(player)) {
                    continue;
                }

                int stock = stockData.resolveStock(playerUuid, this, offerDefinition, nowMillis);
                runtimeOffers.add(offerDefinition.createRuntimeOffer(stock));
            }
            if (!runtimeOffers.isEmpty()) {
                runtimeShop.add(new Category(categoryDefinition.name(), runtimeOffers));
            }
        }
        return runtimeShop;
    }
}
