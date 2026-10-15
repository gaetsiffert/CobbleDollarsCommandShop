package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;

import fr.harmex.cobbledollars.common.api.shop.Offer;
import fr.cobbledollars.commandshops.CobbleDollarsOffers;
import net.minecraft.world.item.ItemStack;

public record ResolvedShopOffer(ShopOfferDefinition source, ItemStack itemStack, ItemMatchAtom.Kind matchKind, String stockKey) {
    public ResolvedShopOffer {
        itemStack = itemStack.copy();
    }

    @Override
    public ItemStack itemStack() {
        return itemStack.copy();
    }

    public String sourceId() {
        return source.id();
    }

    public BigInteger price() {
        return source.price();
    }

    public int maxStock() {
        return source.stock();
    }

    public RestockRule restockRule() {
        return source.restockRule();
    }

    public boolean hasFiniteStock() {
        return source.hasFiniteStock();
    }

    public boolean hasRestockRule() {
        return source.hasRestockRule();
    }

    ItemStack template() {
        return itemStack;
    }

    public Offer createRuntimeOffer(int currentStock) {
        return CobbleDollarsOffers.createShopOffer(itemStack, price(), currentStock);
    }
}
