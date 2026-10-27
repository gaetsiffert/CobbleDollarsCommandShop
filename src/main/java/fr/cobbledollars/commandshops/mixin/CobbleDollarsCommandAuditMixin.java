package fr.cobbledollars.commandshops.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.cobbledollars.commandshops.audit.BalanceAdjustmentAction;
import fr.cobbledollars.commandshops.audit.EconomyAuditService;
import fr.harmex.cobbledollars.common.command.CobbleDollarsCommand;
import java.math.BigInteger;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = CobbleDollarsCommand.class, remap = false)
public abstract class CobbleDollarsCommandAuditMixin {
    @WrapMethod(method = "pay(Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/server/level/ServerPlayer;Ljava/math/BigInteger;)I", remap = false)
    private int cobbledollarscommandshops$auditPayment(CommandSourceStack source, ServerPlayer recipient,
                                                       BigInteger amount, Operation<Integer> original) {
        return EconomyAuditService.transfer(source.getPlayer(), recipient, () -> original.call(source, recipient, amount));
    }

    @WrapMethod(method = "give(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Ljava/math/BigInteger;)I", remap = false)
    private int cobbledollarscommandshops$auditGive(CommandSourceStack source, Collection<? extends ServerPlayer> players,
                                                    BigInteger amount, Operation<Integer> original) {
        return EconomyAuditService.adjust(source, players, BalanceAdjustmentAction.GIVE, amount,
                () -> original.call(source, players, amount));
    }

    @WrapMethod(method = "remove(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Ljava/math/BigInteger;)I", remap = false)
    private int cobbledollarscommandshops$auditRemove(CommandSourceStack source, Collection<? extends ServerPlayer> players,
                                                      BigInteger amount, Operation<Integer> original) {
        return EconomyAuditService.adjust(source, players, BalanceAdjustmentAction.REMOVE, amount,
                () -> original.call(source, players, amount));
    }

    @WrapMethod(method = "set(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;Ljava/math/BigInteger;)I", remap = false)
    private int cobbledollarscommandshops$auditSet(CommandSourceStack source, Collection<? extends ServerPlayer> players,
                                                   BigInteger amount, Operation<Integer> original) {
        return EconomyAuditService.adjust(source, players, BalanceAdjustmentAction.SET, amount,
                () -> original.call(source, players, amount));
    }
}
