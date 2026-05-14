package fr.cobbledollars.commandshops.shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class BankRuntimeBuilder {
    private final List<BankCategoryDefinition> categories;
    private final ConditionSet bankConditions;

    BankRuntimeBuilder(List<BankCategoryDefinition> categories, ConditionSet bankConditions) {
        this.categories = categories;
        this.bankConditions = bankConditions;
    }

    BankDefinition.RuntimeBankData createRuntimeData(ServerPlayer player) {
        ConditionSet.ConditionContext context = ConditionSet.ConditionContext.capture(player);
        if (!bankConditions.test(context)) {
            return BankDefinition.emptyData();
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
                        exactCandidates.merge(candidate.displayKey(), candidate, BankRuntimeBuilder::selectBetterCandidate);
                    } else {
                        genericCandidates.merge(candidate.offer().getItem().getItem(), candidate, BankRuntimeBuilder::selectBetterCandidate);
                    }
                    displayWinners.merge(candidate.displayKey(), candidate, BankRuntimeBuilder::selectBetterCandidate);
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
        return new BankDefinition.RuntimeBankData(
                new Bank(runtimeOffers),
                exactOffers,
                genericOffers,
                List.copyOf(exactOffersByKey.values()),
                List.copyOf(genericOffers.values())
        );
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

    private record BankLookupKey(Item item, DataComponentMap components) {
        private static BankLookupKey from(ItemStack stack) {
            return new BankLookupKey(stack.getItem(), stack.getComponents());
        }
    }

    private record BankOfferCandidate(int sourceOrder, BankLookupKey displayKey, ItemMatchAtom.Kind kind, Offer offer) {
    }
}
