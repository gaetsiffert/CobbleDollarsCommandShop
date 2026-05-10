package fr.cobbledollars.commandshops.feedback;

import java.math.BigInteger;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class FeedbackFormatter {
    private FeedbackFormatter() {
    }

    public static Component shopDenied(ShopDefinition shop) {
        String denyMessage = shop.denyMessage();
        if (denyMessage == null || denyMessage.isBlank()) {
            denyMessage = "You cannot access this shop yet.";
        }
        return Component.literal(denyMessage);
    }

    public static Component buySuccess(
            ItemStack bundleTemplate,
            int amount,
            BigInteger totalPrice,
            int stockLeft
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("Bought ")
                .append(formatStackAmount(bundleTemplate, amount))
                .append(" for ")
                .append(totalPrice)
                .append(".");
        if (stockLeft >= 0) {
            builder.append(" Stock left: ").append(stockLeft).append(".");
        }
        return Component.literal(builder.toString());
    }

    public static Component buyFailure(
            BuyFailureReason reason
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(switch (reason) {
            case NOT_ENOUGH_MONEY -> "Not enough CobbleDollars.";
            case NOT_ENOUGH_SPACE -> "Not enough inventory space.";
            case OUT_OF_STOCK -> "Out of stock.";
            case OFFER_CHANGED -> "Offer changed. Shop refreshed.";
            case OFFER_UNAVAILABLE -> "This offer is no longer available.";
        });
        return Component.literal(builder.toString());
    }

    public static Component sellSuccess(int soldItemCount, BigInteger totalValue) {
        return Component.literal("Sold " + soldItemCount + (soldItemCount == 1 ? " item" : " items") + " for " + totalValue + ".");
    }

    public static Component sellFailure(SellFailureReason reason) {
        return switch (reason) {
            case NOTHING_SELLABLE -> Component.literal("No sellable items in the bank.");
        };
    }

    private static String formatStackAmount(ItemStack bundleTemplate, int amount) {
        int totalCount = Math.multiplyExact(bundleTemplate.getCount(), amount);
        return totalCount + "x " + bundleTemplate.getHoverName().getString();
    }

}
