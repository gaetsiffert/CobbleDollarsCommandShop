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
    private final List<String> sortedOfferIds;
    private final List<PreparedCategory> preparedCategories;
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
        this.sortedOfferIds = offersById.keySet().stream().sorted().toList();
        this.preparedCategories = prepareCategories(this.categories);
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

    private static List<PreparedCategory> prepareCategories(List<ShopCategoryDefinition> categories) {
        ArrayList<PreparedCategory> preparedCategories = new ArrayList<>(categories.size());
        int sourceOrder = 0;
        for (ShopCategoryDefinition category : categories) {
            ArrayList<PreparedOfferGroup> preparedOfferGroups = new ArrayList<>(category.offers().size());
            for (ShopOfferDefinition offerDefinition : category.offers()) {
                List<ResolvedShopOffer> resolvedOffers = offerDefinition.createResolvedOffers();
                ArrayList<PreparedCandidate> candidates = new ArrayList<>(resolvedOffers.size());
                for (ResolvedShopOffer resolvedOffer : resolvedOffers) {
                    candidates.add(new PreparedCandidate(displayKey(resolvedOffer.template()), sourceOrder, resolvedOffer));
                }
                preparedOfferGroups.add(new PreparedOfferGroup(offerDefinition, List.copyOf(candidates)));
                sourceOrder++;
            }
            preparedCategories.add(new PreparedCategory(category.name(), category.conditions(), List.copyOf(preparedOfferGroups)));
        }
        return List.copyOf(preparedCategories);
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
        return sortedOfferIds;
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
        return getVisibleOffer(ConditionSet.ConditionContext.capture(player), categoryIndex, offerIndex);
    }

    ShopOfferDefinition getVisibleOffer(ConditionSet.ConditionContext context, int categoryIndex, int offerIndex) {
        if (categoryIndex < 0 || offerIndex < 0 || !conditions.test(context)) {
            return null;
        }

        int visibleCategoryIndex = 0;
        for (ShopCategoryDefinition categoryDefinition : categories) {
            if (!categoryDefinition.conditions().test(context)) {
                continue;
            }

            int visibleOfferIndex = 0;
            for (ShopOfferDefinition offerDefinition : categoryDefinition.offers()) {
                if (!offerDefinition.isVisibleTo(context)) {
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
        return createRuntimeData(stockData, player, nowMillis, null);
    }

    RuntimeShopData createRuntimeData(
            PlayerShopStockData stockData,
            ServerPlayer player,
            long nowMillis,
            CreateRuntimeDataMetrics metrics
    ) {
        long phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        Shop runtimeShop = new Shop();
        ConditionSet.ConditionContext context = ConditionSet.ConditionContext.capture(player);
        if (metrics != null) {
            metrics.contextNanos += System.nanoTime() - phaseStartNanos;
        }
        if (!conditions.test(context)) {
            return new RuntimeShopData(runtimeShop, List.of(), 0, Long.MAX_VALUE);
        }

        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        ArrayList<VisibleCategory> visibleCategories = new ArrayList<>(preparedCategories.size());
        HashMap<ShopDisplayKey, PreparedCandidate> winners = new HashMap<>();
        for (PreparedCategory category : preparedCategories) {
            if (!category.conditions().test(context)) {
                continue;
            }

            ArrayList<PreparedOfferGroup> visibleOfferGroups = new ArrayList<>(category.offerGroups().size());
            for (PreparedOfferGroup offerGroup : category.offerGroups()) {
                if (!offerGroup.offerDefinition().isVisibleTo(context)) {
                    continue;
                }
                visibleOfferGroups.add(offerGroup);
                for (PreparedCandidate candidate : offerGroup.candidates()) {
                    winners.merge(candidate.displayKey(), candidate, ShopDefinition::selectBetterCandidate);
                }
            }
            if (!visibleOfferGroups.isEmpty()) {
                visibleCategories.add(new VisibleCategory(category.name(), List.copyOf(visibleOfferGroups)));
            }
        }
        if (metrics != null) {
            metrics.candidateSelectionNanos += System.nanoTime() - phaseStartNanos;
        }

        phaseStartNanos = metrics == null ? 0L : System.nanoTime();
        UUID playerUuid = player.getUUID();
        ArrayList<RuntimeCategory> runtimeCategories = new ArrayList<>(visibleCategories.size());
        int visibleOfferCount = 0;
        long nextVisibleRestockAtMillis = Long.MAX_VALUE;
        for (VisibleCategory category : visibleCategories) {
            int categoryOfferCapacity = 0;
            for (PreparedOfferGroup offerGroup : category.offerGroups()) {
                categoryOfferCapacity += offerGroup.candidates().size();
            }
            ArrayList<Offer> cobbleOffers = new ArrayList<>(categoryOfferCapacity);
            ArrayList<RuntimeShopOfferEntry> resolvedOffers = new ArrayList<>(categoryOfferCapacity);
            for (PreparedOfferGroup offerGroup : category.offerGroups()) {
                for (PreparedCandidate candidate : offerGroup.candidates()) {
                    if (winners.get(candidate.displayKey()) != candidate) {
                        continue;
                    }

                    PlayerShopStockData.OfferRuntimeState offerState = stockData.resolveOfferRuntimeState(playerUuid, this, candidate.offer(), nowMillis);
                    Offer runtimeOffer = candidate.offer().createRuntimeOffer(offerState.stock());
                    cobbleOffers.add(runtimeOffer);
                    resolvedOffers.add(new RuntimeShopOfferEntry(candidate.offer(), runtimeOffer, offerState.restockPreview()));
                    if (offerState.restockPreview().hasNextRestock()) {
                        nextVisibleRestockAtMillis = Math.min(nextVisibleRestockAtMillis, offerState.restockPreview().nextRestockAtMillis());
                    }
                }
            }
            if (!cobbleOffers.isEmpty()) {
                runtimeShop.add(new Category(category.name(), cobbleOffers));
                runtimeCategories.add(new RuntimeCategory(category.name(), List.copyOf(resolvedOffers)));
                visibleOfferCount += resolvedOffers.size();
            }
        }
        if (metrics != null) {
            metrics.materializationNanos += System.nanoTime() - phaseStartNanos;
        }
        return new RuntimeShopData(runtimeShop, List.copyOf(runtimeCategories), visibleOfferCount, nextVisibleRestockAtMillis);
    }

    private static PreparedCandidate selectBetterCandidate(PreparedCandidate current, PreparedCandidate incoming) {
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

    private record PreparedCategory(String name, ConditionSet conditions, List<PreparedOfferGroup> offerGroups) {
    }

    private record VisibleCategory(String name, List<PreparedOfferGroup> offerGroups) {
    }

    private record PreparedOfferGroup(ShopOfferDefinition offerDefinition, List<PreparedCandidate> candidates) {
    }

    private record PreparedCandidate(ShopDisplayKey displayKey, int sourceOrder, ResolvedShopOffer offer) {
    }

    private record ShopDisplayKey(Item item, DataComponentMap components) {
    }

    public record RuntimeShopData(Shop shop, List<RuntimeCategory> categories, int visibleOfferCount, long nextVisibleRestockAtMillis) {
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

    public record RuntimeShopOfferEntry(ResolvedShopOffer resolvedOffer, Offer runtimeOffer, PlayerShopStockData.RestockPreview restockPreview) {
    }

    static final class CreateRuntimeDataMetrics {
        private long contextNanos;
        private long candidateSelectionNanos;
        private long materializationNanos;

        long contextNanos() {
            return contextNanos;
        }

        long candidateSelectionNanos() {
            return candidateSelectionNanos;
        }

        long materializationNanos() {
            return materializationNanos;
        }
    }
}
