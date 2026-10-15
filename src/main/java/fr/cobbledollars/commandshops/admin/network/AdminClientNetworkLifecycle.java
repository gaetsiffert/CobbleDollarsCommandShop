package fr.cobbledollars.commandshops.admin.network;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Registered only on the physical client; the common protocol never references this class. */
@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID, value = Dist.CLIENT)
public final class AdminClientNetworkLifecycle {
    private AdminClientNetworkLifecycle() {
    }

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        AdminNetwork.beginClientConnection();
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        AdminNetwork.clearClientState();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        AdminNetwork.tickClient();
    }
}
