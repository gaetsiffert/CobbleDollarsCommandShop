package fr.cobbledollars.commandshops.shop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        if (!conditions.test(player)) {
            return emptyData();
        }

        ArrayList<Offer> runtimeOffers = new ArrayList<>();
        HashMap<BankLookupKey, Offer> offersByKey = new HashMap<>();
        for (BankCategoryDefinition category : categories) {
            if (!category.conditions().test(player)) {
                continue;
            }
            for (BankOfferDefinition offer : category.offers()) {
                if (!offer.isVisibleTo(player)) {
                    continue;
                }
                Offer runtimeOffer = offer.createRuntimeOffer();
                runtimeOffers.add(runtimeOffer);
                offersByKey.putIfAbsent(BankLookupKey.from(runtimeOffer.getItem()), runtimeOffer);
            }
        }
        return new RuntimeBankData(new Bank(runtimeOffers), Map.copyOf(offersByKey));
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
        return new RuntimeBankData(emptyBank(), Map.of());
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

    public record RuntimeBankData(Bank bank, Map<BankLookupKey, Offer> offersByKey) {
        public Offer get(ItemStack stack) {
            return offersByKey.get(BankLookupKey.from(stack));
        }
    }
}
