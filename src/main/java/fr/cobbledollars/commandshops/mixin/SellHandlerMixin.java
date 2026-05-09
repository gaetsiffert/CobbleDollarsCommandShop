package fr.cobbledollars.commandshops.mixin;

import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.harmex.cobbledollars.common.network.handlers.server.SellHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.SellPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SellHandler.class, remap = false)
public abstract class SellHandlerMixin {
    @Inject(
            method = "handle(Lfr/harmex/cobbledollars/common/network/packets/c2s/SellPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cobbledollarscommandshops$handleCustomBankSell(SellPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callbackInfo) {
        if (CommandShopSessions.handleCustomSell(server, player)) {
            callbackInfo.cancel();
        }
    }
}
