package fr.cobbledollars.commandshops;

import java.math.BigInteger;

import fr.harmex.cobbledollars.common.api.shop.Offer;
import net.minecraft.world.item.ItemStack;

public final class CobbleDollarsOffers {
    private CobbleDollarsOffers() {
    }

    public static Offer createShopOffer(ItemStack template, BigInteger price, int stock) {
        return new Offer(template.copy(), price, stock);
    }

    // Beta 6 normalizes Offer.getItems() to unit stacks. Command shop prices and
    // quantities still describe bundles, whose count is retained by the ingredient.
    public static ItemStack getItem(Offer offer) {
        return getItem(offer, 0);
    }

    public static ItemStack getItem(Offer offer, int index) {
        ItemStack[] items = offer.getIngredient().getItems();
        if (index < 0 || index >= items.length) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = items[index].copy();
        stack.applyComponents(offer.getComponents());
        return stack;
    }

    public static boolean equalsWithoutStock(Offer expected, Offer actual) {
        if (actual == null || !expected.getPrice().equals(actual.getPrice())) {
            return false;
        }
        // Native stock updates rebuild offers from unit stacks. Bundle counts
        // always come from our server-side definition, not the submitted offer.
        return ItemStack.listMatches(expected.getItems(), actual.getItems());
    }
}
