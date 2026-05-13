package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class BankOfferDefinition {
    private final ItemMatchExpression match;
    private final BigInteger price;
    private final ConditionSet conditions;
    private volatile List<ResolvedBankOffer> resolvedOffers;

    public BankOfferDefinition(ItemMatchExpression match, BigInteger price, ConditionSet conditions) {
        this.match = match;
        this.price = price;
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
    }

    public ItemMatchExpression match() {
        return match;
    }

    public ItemStack createDisplayStack() {
        List<ResolvedBankOffer> offers = createResolvedOffers();
        if (offers.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return offers.get(0).itemStack();
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

    boolean isVisibleTo(ConditionSet.ConditionContext context) {
        return conditions.test(context);
    }

    public List<ResolvedBankOffer> createResolvedOffers() {
        List<ResolvedBankOffer> cachedOffers = resolvedOffers;
        if (cachedOffers != null) {
            return cachedOffers;
        }

        synchronized (this) {
            cachedOffers = resolvedOffers;
            if (cachedOffers == null) {
                cachedOffers = resolveOffers();
                resolvedOffers = cachedOffers;
            }
        }
        return cachedOffers;
    }

    private List<ResolvedBankOffer> resolveOffers() {
        List<ItemMatchExpression.ResolvedMatch> matches = match.resolveMatches(1);
        if (matches.isEmpty()) {
            return List.of();
        }

        ArrayList<ResolvedBankOffer> offers = new ArrayList<>(matches.size());
        for (ItemMatchExpression.ResolvedMatch matchResult : matches) {
            offers.add(new ResolvedBankOffer(this, matchResult.stack(), matchResult.kind()));
        }
        return List.copyOf(offers);
    }
}
