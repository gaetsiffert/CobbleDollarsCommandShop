package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class BankOfferDefinition {
    private final ItemStack itemTemplate;
    private final BigInteger price;
    private final ConditionSet conditions;

    public BankOfferDefinition(ItemStack itemTemplate, BigInteger price, ConditionSet conditions) {
        this.itemTemplate = itemTemplate.copy();
        this.price = price;
        this.conditions = conditions;
    }

    public ItemStack createItemStack() {
        return itemTemplate.copy();
    }

    public BigInteger price() {
        return price;
    }

    public ConditionSet conditions() {
        return conditions;
    }

    public boolean isVisibleTo(ServerPlayer player) {
        return conditions.test(player);
    }

    public Offer createRuntimeOffer() {
        return new Offer(createItemStack(), price, -1);
    }
}
