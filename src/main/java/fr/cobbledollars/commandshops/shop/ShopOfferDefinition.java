package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.world.item.ItemStack;

public final class ShopOfferDefinition {
    private final String id;
    private final ItemStack itemTemplate;
    private final BigInteger price;
    private final int stock;
    private final RestockRule restockRule;

    public ShopOfferDefinition(String id, ItemStack itemTemplate, BigInteger price, int stock, RestockRule restockRule) {
        this.id = id;
        this.itemTemplate = itemTemplate.copy();
        this.price = price;
        this.stock = stock;
        this.restockRule = restockRule;
    }

    public String id() {
        return id;
    }

    public BigInteger price() {
        return price;
    }

    public int stock() {
        return stock;
    }

    public RestockRule restockRule() {
        return restockRule;
    }

    public boolean hasFiniteStock() {
        return stock >= 0;
    }

    public ItemStack createItemStack() {
        return itemTemplate.copy();
    }

    public Offer createRuntimeOffer(int currentStock) {
        return new Offer(createItemStack(), price, currentStock);
    }
}
