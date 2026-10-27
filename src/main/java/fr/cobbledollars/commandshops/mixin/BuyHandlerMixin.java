package fr.cobbledollars.commandshops.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.cobbledollars.commandshops.audit.EconomyAuditService;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.harmex.cobbledollars.common.network.handlers.server.BuyHandler;
import fr.harmex.cobbledollars.common.network.packets.c2s.BuyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BuyHandler.class, remap = false)
public abstract class BuyHandlerMixin {
    @Inject(
            method = "handle(Lfr/harmex/cobbledollars/common/network/packets/c2s/BuyPacket;Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cobbledollarscommandshops$handleCommandShopBuy(BuyPacket packet, MinecraftServer server, ServerPlayer player, CallbackInfo callbackInfo) {
        if (CommandShopSessions.handleCustomBuy(packet, server, player)) {
            callbackInfo.cancel();
        }
    }

    @WrapMethod(method = "buy(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;Ljava/math/BigInteger;)V", remap = false)
    private void cobbledollarscommandshops$auditNativeBuy(ServerPlayer player, ItemStack items, BigInteger price,
                                                          Operation<Void> original) {
        EconomyAuditService.nativeBuy(player, items, () -> original.call(player, items, price));
    }
}
