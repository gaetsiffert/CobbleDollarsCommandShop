package fr.cobbledollars.commandshops.shop;

import java.util.List;

public record ShopCategoryDefinition(String name, List<ShopOfferDefinition> offers) {
    public ShopCategoryDefinition {
        offers = List.copyOf(offers);
    }
}
