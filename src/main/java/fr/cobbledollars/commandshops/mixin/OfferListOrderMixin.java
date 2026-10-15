package fr.cobbledollars.commandshops.mixin;

import java.util.Comparator;
import java.util.List;

import fr.cobbledollars.commandshops.client.ClientUiState;
import fr.harmex.cobbledollars.common.api.shop.Offer;
import fr.harmex.cobbledollars.common.client.gui.screen.ShopScreen;
import fr.harmex.cobbledollars.common.client.gui.screen.widget.OfferListWidget;
import kotlin.collections.CollectionsKt;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native CobbleDollars orders offers by price; command shops follow their configured order. */
@Mixin(value = OfferListWidget.class, remap = false)
public abstract class OfferListOrderMixin {
    @Shadow @Final private ShopScreen shopScreen;

    @Redirect(
            method = {
                    "createOfferEntries(Lfr/harmex/cobbledollars/common/api/shop/Category;Z)V",
                    "search(Ljava/lang/String;)V"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lkotlin/collections/CollectionsKt;sortedWith(Ljava/lang/Iterable;Ljava/util/Comparator;)Ljava/util/List;"
            ),
            remap = false
    )
    private List<Offer> cobbledollarscommandshops$keepConfiguredOfferOrder(
            Iterable<Offer> offers, Comparator<? super Offer> nativeComparator
    ) {
        return ClientUiState.isCommandShop(shopScreen)
                ? CollectionsKt.toList(offers)
                : CollectionsKt.sortedWith(offers, nativeComparator);
    }
}
