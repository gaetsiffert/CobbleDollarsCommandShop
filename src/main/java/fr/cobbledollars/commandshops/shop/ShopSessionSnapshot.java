package fr.cobbledollars.commandshops.shop;

import java.util.ArrayList;
import java.util.List;

final class ShopSessionSnapshot {
    private final List<CategorySnapshot> categories;

    ShopSessionSnapshot(List<CategorySnapshot> categories) {
        this.categories = categories;
    }

    static ShopSessionSnapshot fromRuntimeData(ShopDefinition.RuntimeShopData runtimeData) {
        return fromRuntimeCategories(runtimeData.categories());
    }

    static ShopSessionSnapshot fromRuntimeCategories(List<ShopDefinition.RuntimeCategory> runtimeCategories) {
        ArrayList<CategorySnapshot> categorySnapshots = new ArrayList<>(runtimeCategories.size());
        for (ShopDefinition.RuntimeCategory category : runtimeCategories) {
            ArrayList<OfferSnapshot> offerSnapshots = new ArrayList<>(category.offers().size());
            for (ShopDefinition.RuntimeShopOfferEntry entry : category.offers()) {
                offerSnapshots.add(new OfferSnapshot(entry.resolvedOffer(), entry.runtimeOffer().getStock()));
            }
            categorySnapshots.add(new CategorySnapshot(category.name(), List.copyOf(offerSnapshots)));
        }
        return new ShopSessionSnapshot(List.copyOf(categorySnapshots));
    }

    boolean hasSameLayout(ShopSessionSnapshot other) {
        if (categories.size() != other.categories.size()) {
            return false;
        }
        for (int categoryIndex = 0; categoryIndex < categories.size(); categoryIndex++) {
            CategorySnapshot previousCategory = categories.get(categoryIndex);
            CategorySnapshot currentCategory = other.categories.get(categoryIndex);
            if (!previousCategory.name().equals(currentCategory.name())) {
                return false;
            }
            if (previousCategory.offers().size() != currentCategory.offers().size()) {
                return false;
            }
            for (int offerIndex = 0; offerIndex < previousCategory.offers().size(); offerIndex++) {
                if (previousCategory.offers().get(offerIndex).resolvedOffer()
                        != currentCategory.offers().get(offerIndex).resolvedOffer()) {
                    return false;
                }
            }
        }
        return true;
    }

    int stockAt(int categoryIndex, int offerIndex) {
        return categories.get(categoryIndex).offers().get(offerIndex).stock();
    }

    record CategorySnapshot(String name, List<OfferSnapshot> offers) {
    }

    record OfferSnapshot(ResolvedShopOffer resolvedOffer, int stock) {
    }
}
