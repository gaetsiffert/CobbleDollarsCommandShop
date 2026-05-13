package fr.cobbledollars.commandshops.shop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Category;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Shop;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ShopDefinition {
    private final String id;
    private final List<ShopCategoryDefinition> categories;
    private final ConditionSet conditions;
    private final String denyMessage;
    private final Path sourceFile;
    private final Map<String, ShopOfferDefinition> offersById;
    private final List<ShopOfferDefinition> offersInOrder;
    private final List<ConditionSet> allConditions;
    private final boolean hasPlayerStateConditions;
    private final boolean hasDimensionConditions;
    private final boolean hasTimeConditions;
    private final boolean hasRestockingOffers;

    public ShopDefinition(String id, List<ShopCategoryDefinition> categories, ConditionSet conditions, String denyMessage, Path sourceFile) {
        this.id = id;
        this.categories = List.copyOf(categories);
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
        this.denyMessage = denyMessage;
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

    public String denyMessage() {
        return denyMessage;
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
        return createRuntimeData(stockData, player, nowMillis).shop();
    }

    public RuntimeShopData createRuntimeData(PlayerShopStockData stockData, ServerPlayer player, long nowMillis) {
        Shop runtimeShop = new Shop();
        if (!conditions.test(player)) {
            return new RuntimeShopData(runtimeShop, List.of());
        }

        ArrayList<SourceCategoryCandidates> categoryContexts = new ArrayList<>();
        ArrayList<ResolvedShopCandidate> allCandidates = new ArrayList<>();
        int sourceOrder = 0;
        for (ShopCategoryDefinition categoryDefinition : categories) {
            if (!categoryDefinition.conditions().test(player)) {
                continue;
            }

            ArrayList<ResolvedShopCandidate> categoryCandidates = new ArrayList<>();
            for (ShopOfferDefinition offerDefinition : categoryDefinition.offers()) {
                if (!offerDefinition.isVisibleTo(player)) {
                    continue;
                }
                for (ResolvedShopOffer resolvedOffer : offerDefinition.createResolvedOffers()) {
                    ResolvedShopCandidate candidate = new ResolvedShopCandidate(sourceOrder, resolvedOffer);
                    categoryCandidates.add(candidate);
                    allCandidates.add(candidate);
                }
                sourceOrder++;
            }
            categoryContexts.add(new SourceCategoryCandidates(categoryDefinition.name(), List.copyOf(categoryCandidates)));
        }

        HashMap<ShopDisplayKey, ResolvedShopCandidate> winners = new HashMap<>();
        for (ResolvedShopCandidate candidate : allCandidates) {
            winners.merge(displayKey(candidate.offer().itemStack()), candidate, ShopDefinition::selectBetterCandidate);
        }

        UUID playerUuid = player.getUUID();
        ArrayList<RuntimeCategory> runtimeCategories = new ArrayList<>();
        for (SourceCategoryCandidates categoryContext : categoryContexts) {
            ArrayList<Offer> cobbleOffers = new ArrayList<>(categoryContext.candidates().size());
            ArrayList<RuntimeShopOfferEntry> resolvedOffers = new ArrayList<>(categoryContext.candidates().size());
            for (ResolvedShopCandidate candidate : categoryContext.candidates()) {
                if (winners.get(displayKey(candidate.offer().itemStack())) != candidate) {
                    continue;
                }

                int stock = stockData.resolveStock(playerUuid, this, candidate.offer(), nowMillis);
                Offer runtimeOffer = candidate.offer().createRuntimeOffer(stock);
                cobbleOffers.add(runtimeOffer);
                resolvedOffers.add(new RuntimeShopOfferEntry(candidate.offer(), runtimeOffer));
            }
            if (!cobbleOffers.isEmpty()) {
                runtimeShop.add(new Category(categoryContext.name(), cobbleOffers));
                runtimeCategories.add(new RuntimeCategory(categoryContext.name(), List.copyOf(resolvedOffers)));
            }
        }
        return new RuntimeShopData(runtimeShop, List.copyOf(runtimeCategories));
    }

    private static ResolvedShopCandidate selectBetterCandidate(ResolvedShopCandidate current, ResolvedShopCandidate incoming) {
        int currentPriority = current.offer().matchKind().priority();
        int incomingPriority = incoming.offer().matchKind().priority();
        if (incomingPriority > currentPriority) {
            return incoming;
        }
        if (incomingPriority < currentPriority) {
            return current;
        }
        return incoming.sourceOrder() < current.sourceOrder() ? incoming : current;
    }

    private static ShopDisplayKey displayKey(ItemStack stack) {
        return new ShopDisplayKey(stack.getItem(), stack.getComponents());
    }

    private record SourceCategoryCandidates(String name, List<ResolvedShopCandidate> candidates) {
    }

    private record ResolvedShopCandidate(int sourceOrder, ResolvedShopOffer offer) {
    }

    private record ShopDisplayKey(Item item, DataComponentMap components) {
    }

    public record RuntimeShopData(Shop shop, List<RuntimeCategory> categories) {
        public RuntimeShopOfferEntry getEntry(int categoryIndex, int offerIndex) {
            if (categoryIndex < 0 || categoryIndex >= categories.size()) {
                return null;
            }
            List<RuntimeShopOfferEntry> offers = categories.get(categoryIndex).offers();
            if (offerIndex < 0 || offerIndex >= offers.size()) {
                return null;
            }
            return offers.get(offerIndex);
        }

        public ResolvedShopOffer getResolvedOffer(int categoryIndex, int offerIndex) {
            RuntimeShopOfferEntry entry = getEntry(categoryIndex, offerIndex);
            return entry == null ? null : entry.resolvedOffer();
        }

        public Offer getRuntimeOffer(int categoryIndex, int offerIndex) {
            RuntimeShopOfferEntry entry = getEntry(categoryIndex, offerIndex);
            return entry == null ? null : entry.runtimeOffer();
        }
    }

    public record RuntimeCategory(String name, List<RuntimeShopOfferEntry> offers) {
    }

    public record RuntimeShopOfferEntry(ResolvedShopOffer resolvedOffer, Offer runtimeOffer) {
    }
}
