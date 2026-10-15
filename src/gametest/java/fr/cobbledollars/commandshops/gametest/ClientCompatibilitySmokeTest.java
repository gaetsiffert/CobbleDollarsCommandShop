package fr.cobbledollars.commandshops.gametest;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID, value = Dist.CLIENT)
public final class ClientCompatibilitySmokeTest {
    private static boolean completed;

    private ClientCompatibilitySmokeTest() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) throws ClassNotFoundException {
        if (completed || !Boolean.getBoolean("commandshops.clientCompatibilitySmokeTest")) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TitleScreen && minecraft.getOverlay() == null) {
            Class.forName("fr.harmex.cobbledollars.common.api.shop.Offer");
            Class.forName("fr.cobbledollars.commandshops.client.ClientUiState");
            completed = true;
            CobbleDollarsCommandShopsMod.LOGGER.info("CLIENT_COMPATIBILITY_RESULT: title screen and optional shop UI loaded.");
            minecraft.stop();
        }
    }
}
