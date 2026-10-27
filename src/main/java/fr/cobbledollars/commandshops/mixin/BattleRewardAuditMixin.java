package fr.cobbledollars.commandshops.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import fr.cobbledollars.commandshops.audit.EconomyAuditService;
import fr.harmex.cobbledollars.common.utils.MiscUtilsKt;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = MiscUtilsKt.class, remap = false)
public abstract class BattleRewardAuditMixin {
    @WrapMethod(method = "calculateAndAwardCobbleDollars(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/List;)V", remap = false)
    private static void cobbledollarscommandshops$auditBattleReward(ServerPlayer player, List<Integer> pokemonLevels,
                                                                    Operation<Void> original) {
        EconomyAuditService.battleReward(player, () -> original.call(player, pokemonLevels));
    }
}
