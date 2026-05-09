package fr.cobbledollars.commandshops.mixin;

import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.harmex.cobbledollars.common.network.handlers.server.OpenShopHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.OpenShopPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OpenShopHandler.class, remap = false)
public abstract class OpenShopHandlerMixin {
    @Inject(
            method = "handle(Lfr/harmex/cobbledollars/common/network/packets/c2s/OpenShopPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cobbledollarscommandshops$reopenCustomShop(OpenShopPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callbackInfo) {
        if (CommandShopSessions.reopenCustomShop(player, packet.getMerchantUUID())) {
            callbackInfo.cancel();
        }
    }
}
