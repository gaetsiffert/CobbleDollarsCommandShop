package fr.cobbledollars.commandshops.client.compat.jei;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.client.ClientUiState;
import fr.harmex.cobbledollars.common.client.gui.screen.BankScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

@JeiPlugin
public final class CommandShopJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_UID = ResourceLocation.parse(CobbleDollarsCommandShopsMod.MODID + ":jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(BankScreen.class, new BankScreenGuiHandler());
    }

    private static final class BankScreenGuiHandler implements IGuiContainerHandler<BankScreen> {
        @Override
        public List<Rect2i> getGuiExtraAreas(BankScreen containerScreen) {
            return ClientUiState.getJeiExtraAreas(containerScreen);
        }
    }
}
