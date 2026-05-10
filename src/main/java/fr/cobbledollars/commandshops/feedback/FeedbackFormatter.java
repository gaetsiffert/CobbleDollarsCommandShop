package fr.cobbledollars.commandshops.feedback;

import java.math.BigInteger;
import fr.cobbledollars.commandshops.shop.ShopDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

public final class FeedbackFormatter {
    private FeedbackFormatter() {
    }

    public static Component shopDenied(ShopDefinition shop) {
        String denyMessage = shop.denyMessage();
        if (denyMessage == null || denyMessage.isBlank()) {
            return Component.translatable("cobbledollarscommandshops.feedback.shop_denied.default");
        }
        return Component.literal(denyMessage);
    }

    public static Component buySuccess(
            ItemStack bundleTemplate,
            int amount,
            BigInteger totalPrice,
            int stockLeft
    ) {
        Component stackAmount = formatStackAmount(bundleTemplate, amount);
        Component moneyAmount = formatMoney(totalPrice);
        if (stockLeft >= 0) {
            return Component.translatable("cobbledollarscommandshops.feedback.buy_success.stock_left", stackAmount, moneyAmount, stockLeft);
        }
        return Component.translatable("cobbledollarscommandshops.feedback.buy_success", stackAmount, moneyAmount);
    }

    public static Component buyFailure(BuyFailureReason reason) {
        return switch (reason) {
            case NOT_ENOUGH_MONEY -> Component.translatable("cobbledollarscommandshops.feedback.buy_failure.not_enough_money");
            case NOT_ENOUGH_SPACE -> Component.translatable("cobbledollarscommandshops.feedback.buy_failure.not_enough_space");
            case OUT_OF_STOCK -> Component.translatable("cobbledollarscommandshops.feedback.buy_failure.out_of_stock");
            case OFFER_CHANGED -> Component.translatable("cobbledollarscommandshops.feedback.buy_failure.offer_changed");
            case OFFER_UNAVAILABLE -> Component.translatable("cobbledollarscommandshops.feedback.buy_failure.offer_unavailable");
        };
    }

    public static Component sellSuccess(int soldItemCount, BigInteger totalValue) {
        return Component.translatable(
                soldItemCount == 1
                        ? "cobbledollarscommandshops.feedback.sell_success.one"
                        : "cobbledollarscommandshops.feedback.sell_success.many",
                soldItemCount,
                formatMoney(totalValue)
        );
    }

    public static Component sellFailure(SellFailureReason reason) {
        return switch (reason) {
            case NOTHING_SELLABLE -> Component.translatable("cobbledollarscommandshops.feedback.sell_failure.nothing_sellable");
        };
    }

    private static MutableComponent formatStackAmount(ItemStack bundleTemplate, int amount) {
        int totalCount = Math.multiplyExact(bundleTemplate.getCount(), amount);
        return Component.translatable("cobbledollarscommandshops.feedback.stack_amount", totalCount, bundleTemplate.getHoverName());
    }

    private static MutableComponent formatMoney(BigInteger amount) {
        return Component.literal(amount.toString());
    }

}
