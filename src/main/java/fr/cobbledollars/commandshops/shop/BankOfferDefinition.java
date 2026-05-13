package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class BankOfferDefinition {
    private final ItemMatchExpression match;
    private final BigInteger price;
    private final ConditionSet conditions;

    public BankOfferDefinition(ItemMatchExpression match, BigInteger price, ConditionSet conditions) {
        this.match = match;
        this.price = price;
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
    }

    public ItemMatchExpression match() {
        return match;
    }

    public ItemStack createDisplayStack() {
        return match.createDisplayStack(1);
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
}
