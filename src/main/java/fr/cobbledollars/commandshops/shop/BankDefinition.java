package fr.cobbledollars.commandshops.shop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class BankDefinition {
    private final List<BankCategoryDefinition> categories;
    private final ConditionSet conditions;
    private final Path sourceFile;
    private final List<ConditionSet> allConditions;
    private final boolean hasPlayerStateConditions;
    private final boolean hasDimensionConditions;
    private final boolean hasTimeConditions;

    public BankDefinition(List<BankCategoryDefinition> categories, ConditionSet conditions, Path sourceFile) {
        this.categories = List.copyOf(categories);
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
        this.sourceFile = sourceFile;
        this.allConditions = collectConditions(this.conditions, categories);
        this.hasPlayerStateConditions = allConditions.stream().anyMatch(ConditionSet::hasPlayerStateConditions);
        this.hasDimensionConditions = allConditions.stream().anyMatch(ConditionSet::hasDimensionConditions);
        this.hasTimeConditions = allConditions.stream().anyMatch(ConditionSet::hasTimeConditions);
    }

    public List<BankCategoryDefinition> categories() {
        return categories;
    }

    public ConditionSet conditions() {
        return conditions;
    }

    public Path sourceFile() {
        return sourceFile;
    }

    public Bank createRuntimeBank(ServerPlayer player) {
        return createRuntimeData(player).bank();
    }

    public RuntimeBankData createRuntimeData(ServerPlayer player) {
        ConditionSet.ConditionContext context = ConditionSet.ConditionContext.capture(player);
        if (!conditions.test(context)) {
            return emptyData();
        }

        HashMap<BankLookupKey, BankOfferCandidate> exactCandidates = new HashMap<>();
        HashMap<Item, BankOfferCandidate> genericCandidates = new HashMap<>();
        LinkedHashMap<BankLookupKey, BankOfferCandidate> displayWinners = new LinkedHashMap<>();
        int sourceOrder = 0;
        for (BankCategoryDefinition category : categories) {
            if (!category.conditions().test(context)) {
                continue;
            }
            for (BankOfferDefinition offer : category.offers()) {
                if (!offer.isVisibleTo(context)) {
                    continue;
                }
                for (ResolvedBankOffer resolvedOffer : offer.createResolvedOffers()) {
                    Offer runtimeOffer = resolvedOffer.createRuntimeOffer();
                    BankLookupKey displayKey = BankLookupKey.from(runtimeOffer.getItem());
                    BankOfferCandidate candidate = new BankOfferCandidate(
                            sourceOrder,
                            displayKey,
                            resolvedOffer.matchKind(),
                            runtimeOffer
                    );
                    if (candidate.kind() == ItemMatchAtom.Kind.STACK) {
                        exactCandidates.merge(candidate.displayKey(), candidate, BankDefinition::selectBetterCandidate);
                    } else {
                        genericCandidates.merge(candidate.offer().getItem().getItem(), candidate, BankDefinition::selectBetterCandidate);
                    }
                    displayWinners.merge(candidate.displayKey(), candidate, BankDefinition::selectBetterCandidate);
                }
                sourceOrder++;
            }
        }

        ArrayList<Offer> runtimeOffers = new ArrayList<>(displayWinners.size());
        IdentityHashMap<Offer, Boolean> includedOffers = new IdentityHashMap<>();
        for (BankOfferCandidate candidate : displayWinners.values()) {
            if (includedOffers.put(candidate.offer(), Boolean.TRUE) == null) {
                runtimeOffers.add(candidate.offer());
            }
        }

        HashMap<BankLookupKey, Offer> exactOffersByKey = new HashMap<>(exactCandidates.size());
        for (Map.Entry<BankLookupKey, BankOfferCandidate> entry : exactCandidates.entrySet()) {
            exactOffersByKey.put(entry.getKey(), entry.getValue().offer());
        }
        HashMap<Item, Offer> genericOffersByItem = new HashMap<>(genericCandidates.size());
        for (Map.Entry<Item, BankOfferCandidate> entry : genericCandidates.entrySet()) {
            genericOffersByItem.put(entry.getKey(), entry.getValue().offer());
        }
        HashMap<Item, Map<DataComponentMap, Offer>> exactOffersByItem = new HashMap<>();
        for (Map.Entry<BankLookupKey, Offer> entry : exactOffersByKey.entrySet()) {
            exactOffersByItem
                    .computeIfAbsent(entry.getKey().item(), ignored -> new HashMap<>())
                    .put(entry.getKey().components(), entry.getValue());
        }

        HashMap<Item, Map<DataComponentMap, Offer>> immutableExactOffersByItem = new HashMap<>(exactOffersByItem.size());
        for (Map.Entry<Item, Map<DataComponentMap, Offer>> entry : exactOffersByItem.entrySet()) {
            immutableExactOffersByItem.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }

        Map<Item, Map<DataComponentMap, Offer>> exactOffers = Map.copyOf(immutableExactOffersByItem);
        Map<Item, Offer> genericOffers = Map.copyOf(genericOffersByItem);
        return new RuntimeBankData(
                new Bank(runtimeOffers),
                exactOffers,
                genericOffers,
                List.copyOf(exactOffersByKey.values()),
                List.copyOf(genericOffers.values())
        );
    }

    public static Bank emptyBank() {
        return new Bank(new ArrayList<>());
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

    public long nextTimeRefreshDelayTicks(long timeOfDay) {
        long nextDelay = Long.MAX_VALUE;
        for (ConditionSet conditionSet : allConditions) {
            nextDelay = Math.min(nextDelay, conditionSet.nextTimeBoundaryDelayTicks(timeOfDay));
        }
        return nextDelay;
    }

    public static RuntimeBankData emptyData() {
        return new RuntimeBankData(emptyBank(), Map.of(), Map.of(), List.of(), List.of());
    }

    private static List<ConditionSet> collectConditions(ConditionSet bankConditions, List<BankCategoryDefinition> categories) {
        ArrayList<ConditionSet> conditionSets = new ArrayList<>();
        if (!bankConditions.isEmpty()) {
            conditionSets.add(bankConditions);
        }
        for (BankCategoryDefinition category : categories) {
            if (!category.conditions().isEmpty()) {
                conditionSets.add(category.conditions());
            }
            for (BankOfferDefinition offer : category.offers()) {
                if (!offer.conditions().isEmpty()) {
                    conditionSets.add(offer.conditions());
                }
            }
        }
        return List.copyOf(conditionSets);
    }

    private record BankLookupKey(Item item, DataComponentMap components) {
        private static BankLookupKey from(ItemStack stack) {
            return new BankLookupKey(stack.getItem(), stack.getComponents());
        }
    }

    private static BankOfferCandidate selectBetterCandidate(BankOfferCandidate current, BankOfferCandidate incoming) {
        if (incoming.kind().priority() > current.kind().priority()) {
            return incoming;
        }
        if (incoming.kind().priority() < current.kind().priority()) {
            return current;
        }
        return incoming.sourceOrder() < current.sourceOrder() ? incoming : current;
    }

    private record BankOfferCandidate(int sourceOrder, BankLookupKey displayKey, ItemMatchAtom.Kind kind, Offer offer) {
    }

    public record RuntimeBankData(
            Bank bank,
            Map<Item, Map<DataComponentMap, Offer>> exactOffersByItem,
            Map<Item, Offer> genericOffersByItem,
            List<Offer> exactOffers,
            List<Offer> genericOffers
    ) {
        public Offer get(ItemStack stack) {
            Map<DataComponentMap, Offer> exactOffers = exactOffersByItem.get(stack.getItem());
            if (exactOffers != null) {
                Offer offer = exactOffers.get(stack.getComponents());
                if (offer != null) {
                    return offer;
                }
            }
            return genericOffersByItem.get(stack.getItem());
        }
    }
}
