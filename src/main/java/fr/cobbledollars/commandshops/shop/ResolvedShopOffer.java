package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.world.item.ItemStack;

public record ResolvedShopOffer(ShopOfferDefinition source, ItemStack itemStack, ItemMatchAtom.Kind matchKind, String stockKey) {
    public ResolvedShopOffer {
        itemStack = itemStack.copy();
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

    public Offer createRuntimeOffer(int currentStock) {
        return new Offer(itemStack.copy(), price(), currentStock);
    }
}
