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

public final class ShopDefinition {
    private final String id;
    private final List<ShopCategoryDefinition> categories;
    private final Path sourceFile;
    private final Map<String, ShopOfferDefinition> offersById;

    public ShopDefinition(String id, List<ShopCategoryDefinition> categories, Path sourceFile) {
        this.id = id;
        this.categories = List.copyOf(categories);
        this.sourceFile = sourceFile;
        this.offersById = buildOfferMap(categories);
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

    public String id() {
        return id;
    }

    public List<ShopCategoryDefinition> categories() {
        return categories;
    }

    public Path sourceFile() {
        return sourceFile;
    }

    public List<ShopOfferDefinition> offers() {
        return List.copyOf(offersById.values());
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

    public Shop createRuntimeShop(PlayerShopStockData stockData, UUID playerUuid, long nowMillis) {
        Shop runtimeShop = new Shop();
        for (ShopCategoryDefinition categoryDefinition : categories) {
            ArrayList<Offer> runtimeOffers = new ArrayList<>(categoryDefinition.offers().size());
            for (ShopOfferDefinition offerDefinition : categoryDefinition.offers()) {
                int stock = stockData.resolveStock(playerUuid, this, offerDefinition, nowMillis);
                runtimeOffers.add(offerDefinition.createRuntimeOffer(stock));
            }
            runtimeShop.add(new Category(categoryDefinition.name(), runtimeOffers));
        }
        return runtimeShop;
    }
}
