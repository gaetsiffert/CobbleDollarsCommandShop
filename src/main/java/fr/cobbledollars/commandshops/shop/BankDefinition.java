package fr.cobbledollars.commandshops.shop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import fr.harmex.cobbledollars.common.world.item.trading.shop.Bank;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.server.level.ServerPlayer;

public final class BankDefinition {
    private final List<BankCategoryDefinition> categories;
    private final ConditionSet conditions;
    private final Path sourceFile;

    public BankDefinition(List<BankCategoryDefinition> categories, ConditionSet conditions, Path sourceFile) {
        this.categories = List.copyOf(categories);
        this.conditions = conditions == null ? ConditionSet.NONE : conditions;
        this.sourceFile = sourceFile;
    }

    public List<BankCategoryDefinition> categories() {
        return categories;
    }

    public ConditionSet conditions() {
        return conditions;
    }

    public Path sourceFile() {
        return sourceFile;
    }

    public Bank createRuntimeBank(ServerPlayer player) {
        if (!conditions.test(player)) {
            return emptyBank();
        }

        ArrayList<Offer> runtimeOffers = new ArrayList<>();
        for (BankCategoryDefinition category : categories) {
            if (!category.conditions().test(player)) {
                continue;
            }
            for (BankOfferDefinition offer : category.offers()) {
                if (!offer.isVisibleTo(player)) {
                    continue;
                }
                runtimeOffers.add(offer.createRuntimeOffer());
            }
        }
        return new Bank(runtimeOffers);
    }

    public static Bank emptyBank() {
        return new Bank(new ArrayList<>());
    }
}
