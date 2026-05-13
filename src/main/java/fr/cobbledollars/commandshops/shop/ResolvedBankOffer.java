package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.world.item.ItemStack;

public record ResolvedBankOffer(BankOfferDefinition source, ItemStack itemStack, ItemMatchAtom.Kind matchKind) {
    public ResolvedBankOffer {
        itemStack = itemStack.copy();
    }

    @Override
    public ItemStack itemStack() {
        return itemStack.copy();
    }

    BigInteger price() {
        return source.price();
    }

    ItemStack template() {
        return itemStack;
    }

    public Offer createRuntimeOffer() {
        return new Offer(itemStack.copy(), price(), -1);
    }
}
