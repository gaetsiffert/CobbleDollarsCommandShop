package fr.cobbledollars.commandshops.mixin;

import fr.cobbledollars.commandshops.client.ClientUiState;
import fr.harmex.cobbledollars.common.api.shop.Offer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Offer.class, remap = false)
public abstract class OfferTooltipMixin {
    @Inject(
            method = "renderTooltip(ILnet/minecraft/client/Minecraft;ZLnet/minecraft/client/gui/GuiGraphics;II)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cobbledollarscommandshops$renderCustomTooltip(int ingredientIndex, Minecraft minecraft, boolean hasShiftDown, GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        if (ClientUiState.renderCustomShopOfferTooltip((Offer) (Object) this, ingredientIndex, minecraft, guiGraphics, mouseX, mouseY)) {
            callbackInfo.cancel();
        }
    }
}
