package fr.cobbledollars.commandshops.shop;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ShopOfferDefinition {
    private final String id;
    private final ItemMatchExpression match;
    private final int count;
    private final BigInteger price;
    private final int stock;
    private final RestockRule restockRule;
    private final ConditionSet conditions;
    private final List<PurchaseBonusDefinition> purchaseBonuses;

    public ShopOfferDefinition(
            String id,
            ItemMatchExpression match,
            int count,
            BigInteger price,
            int stock,
            RestockRule restockRule,
            ConditionSet conditions,
            List<PurchaseBonusDefinition> purchaseBonuses
    ) {
        this.id = id;
        this.match = match;
        this.count = count;
        this.price = price;
        this.stock = stock;
        this.restockRule = restockRule;
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
        this.purchaseBonuses = purchaseBonuses == null ? List.of() : List.copyOf(purchaseBonuses);
        if (stock >= 0) {
            for (PurchaseBonusDefinition purchaseBonus : this.purchaseBonuses) {
                if (purchaseBonus.requiredBundles() > stock) {
                    throw new IllegalArgumentException("Purchase bonus threshold " + purchaseBonus.requiredBundles() + " exceeds finite stock " + stock + " for offer '" + id + "'.");
                }
            }
        }
    }

    public String id() {
        return id;
    }

    public BigInteger price() {
        return price;
    }

    public ItemMatchExpression match() {
        return match;
    }

    public int count() {
        return count;
    }

    public int stock() {
        return stock;
    }

    public RestockRule restockRule() {
        return restockRule;
    }

    public ConditionSet conditions() {
        return conditions;
    }

    public List<PurchaseBonusDefinition> purchaseBonuses() {
        return purchaseBonuses;
    }

    public boolean hasFiniteStock() {
        return stock >= 0;
    }

    public boolean hasRestockRule() {
        return restockRule != null;
    }

    public boolean isVisibleTo(ServerPlayer player) {
        return conditions.test(player);
    }

    public List<ItemStack> createBonusRewardStacks(int purchasedBundles) {
        if (purchaseBonuses.isEmpty() || purchasedBundles <= 0) {
            return List.of();
        }

        ArrayList<ItemStack> rewardStacks = new ArrayList<>();
        for (PurchaseBonusDefinition purchaseBonus : purchaseBonuses) {
            rewardStacks.addAll(purchaseBonus.createRewardStacks(purchasedBundles));
        }
        return List.copyOf(rewardStacks);
    }

    public ItemStack createItemStack() {
        return match.createDisplayStack(count);
    }

    public List<ResolvedShopOffer> createResolvedOffers() {
        List<ItemMatchExpression.ResolvedMatch> matches = match.resolveMatches(count);
        ArrayList<ResolvedShopOffer> offers = new ArrayList<>(matches.size());
        if (matches.isEmpty()) {
            return List.of();
        }

        boolean multipleVariants = matches.size() > 1;
        for (int index = 0; index < matches.size(); index++) {
            ItemMatchExpression.ResolvedMatch matchResult = matches.get(index);
            ItemStack stack = matchResult.stack();
            offers.add(new ResolvedShopOffer(this, stack, matchResult.kind(), createStockKey(stack, multipleVariants, index)));
        }
        return List.copyOf(offers);
    }

    private String createStockKey(ItemStack stack, boolean multipleVariants, int variantIndex) {
        if (!multipleVariants) {
            return id;
        }
        Item item = stack.getItem();
        String itemKey = String.valueOf(BuiltInRegistries.ITEM.getKey(item));
        DataComponentMap components = stack.getComponents();
        if (components.isEmpty()) {
            return id + "@" + itemKey;
        }
        return id + "@" + itemKey + "#" + Integer.toHexString(components.hashCode()) + "_" + variantIndex;
    }
}
