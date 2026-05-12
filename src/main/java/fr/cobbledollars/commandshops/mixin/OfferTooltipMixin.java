package fr.cobbledollars.commandshops.mixin;

import fr.cobbledollars.commandshops.client.ClientUiState;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Offer.class, remap = false)
public abstract class OfferTooltipMixin {
    @Inject(
            method = "renderTooltip(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/GuiGraphics;II)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cobbledollarscommandshops$renderCustomTooltip(Minecraft minecraft, GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        if (ClientUiState.renderCustomShopOfferTooltip((Offer) (Object) this, minecraft, guiGraphics, mouseX, mouseY)) {
            callbackInfo.cancel();
        }
    }
}
