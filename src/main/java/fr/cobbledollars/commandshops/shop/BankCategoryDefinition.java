package fr.cobbledollars.commandshops.shop;

import java.util.List;

public record BankCategoryDefinition(String name, List<BankOfferDefinition> offers, ConditionSet conditions) {
    public BankCategoryDefinition {
        offers = List.copyOf(offers);
        conditions = conditions == null ? ConditionSet.NONE : conditions;
    }
}
