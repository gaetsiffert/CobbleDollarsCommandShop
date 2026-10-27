package fr.cobbledollars.commandshops.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.cobbledollars.commandshops.audit.EconomyAuditService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.harmex.cobbledollars.common.network.handlers.server.SellHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.SellPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = SellHandler.class, remap = false)
public abstract class SellHandlerMixin {
    @WrapMethod(
            method = "handle(Lfr/harmex/cobbledollars/common/network/packets/c2s/SellPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
            remap = false
    )
    private void cobbledollarscommandshops$handleCustomBankSell(SellPacket packet, MinecraftServer server, ServerPlayer player,
                                                                Operation<Void> original) {
        if (CommandShopSessions.handleCustomSell(server, player)) return;
        EconomyAuditService.nativeSell(player, () -> original.call(packet, server, player));
    }
}
