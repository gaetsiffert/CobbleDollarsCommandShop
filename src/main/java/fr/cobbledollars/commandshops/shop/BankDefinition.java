package fr.cobbledollars.commandshops.shop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

import fr.harmex.cobbledollars.common.api.bank.Bank;
import fr.harmex.cobbledollars.common.api.shop.Offer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

public final class BankDefinition {
    private final List<BankCategoryDefinition> categories;
    private final ConditionSet conditions;
    private final String denyMessage;
    private final Path sourceFile;
    private final List<ConditionSet> allConditions;
    private final boolean hasPlayerStateConditions;
    private final boolean hasDimensionConditions;
    private final boolean hasTimeConditions;
    private final BankRuntimeBuilder runtimeBuilder;

    public BankDefinition(List<BankCategoryDefinition> categories, ConditionSet conditions, Path sourceFile) {
        this(categories, conditions, null, sourceFile);
    }

    public BankDefinition(List<BankCategoryDefinition> categories, ConditionSet conditions, String denyMessage, Path sourceFile) {
        this.categories = List.copyOf(categories);
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
        this.denyMessage = denyMessage == null || denyMessage.isBlank() ? null : denyMessage;
        this.sourceFile = sourceFile;
        this.allConditions = collectConditions(this.conditions, categories);
        this.hasPlayerStateConditions = allConditions.stream().anyMatch(ConditionSet::hasPlayerStateConditions);
        this.hasDimensionConditions = allConditions.stream().anyMatch(ConditionSet::hasDimensionConditions);
        this.hasTimeConditions = allConditions.stream().anyMatch(ConditionSet::hasTimeConditions);
        this.runtimeBuilder = new BankRuntimeBuilder(this.categories, this.conditions);
    }

    public List<BankCategoryDefinition> categories() {
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

    public Bank createRuntimeBank(ServerPlayer player) {
        return createRuntimeData(player).bank();
    }

    public RuntimeBankData createRuntimeData(ServerPlayer player) {
        return runtimeBuilder.createRuntimeData(player);
    }

    public static Bank emptyBank() {
        // Native menus replace a bank with no entries by the global bank.
        // Ingredient.EMPTY matches empty slots and makes them glow green. Native
        // Offer.hasItem checks every template's required components, so no stack
        // can match both stone's max stack size (64) and a sword's (1). Vanilla
        // ingredient values and this component rule survive the native codec;
        // custom NeoForge ingredients do not. Keep the shared patch empty so
        // each template retains its different default MAX_STACK_SIZE.
        Offer emptyOffer = new Offer(Ingredient.of(Items.STONE, Items.DIAMOND_SWORD),
                BigInteger.ZERO, DataComponentPatch.EMPTY, -1);
        return new Bank(Map.of(emptyOffer, List.of(DataComponents.MAX_STACK_SIZE)));
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
