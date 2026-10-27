package fr.cobbledollars.commandshops.audit;

import fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt;
import fr.harmex.cobbledollars.common.world.inventory.BankMenu;
import fr.harmex.cobbledollars.common.world.inventory.ShopMenu;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** Operation-local observation of native CobbleDollars actions; no balance setter interception. */
public final class EconomyAuditService {
    private EconomyAuditService() { }

    public static int transfer(ServerPlayer sender, ServerPlayer recipient, IntSupplier operation) {
        if (!AuditLogService.isEventEnabled(AuditEventType.PLAYER_TRANSFER)
                || sender == null || sender.getUUID().equals(recipient.getUUID())) return operation.getAsInt();
        BigInteger senderBefore = balance(sender), recipientBefore = balance(recipient);
        int result = operation.getAsInt();
        AuditLogService.logPlayerTransfer(UUID.randomUUID(), change(sender, senderBefore), change(recipient, recipientBefore));
        return result;
    }

    public static int adjust(CommandSourceStack source, Collection<? extends ServerPlayer> targets,
                             BalanceAdjustmentAction action, BigInteger requested, IntSupplier operation) {
        if (!AuditLogService.isEventEnabled(AuditEventType.BALANCE_ADJUSTED)) return operation.getAsInt();
        Map<ServerPlayer, BigInteger> before = new LinkedHashMap<>();
        for (ServerPlayer target : targets) before.put(target, balance(target));
        int result = operation.getAsInt();
        UUID operationId = UUID.randomUUID();
        ServerPlayer actor = source.getPlayer();
        String actorUuid = actor == null ? null : actor.getUUID().toString();
        for (var entry : before.entrySet()) {
            AuditLogService.logBalanceAdjusted(operationId, source.getTextName(), actorUuid, action, requested,
                    change(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    public static void battleReward(ServerPlayer player, Runnable operation) {
        if (!AuditLogService.isEventEnabled(AuditEventType.BATTLE_REWARD)) { operation.run(); return; }
        BigInteger before = balance(player);
        operation.run();
        AuditLogService.logBattleReward(UUID.randomUUID(), change(player, before));
    }

    public static void nativeBuy(ServerPlayer player, ItemStack items, Runnable operation) {
        if (!AuditLogService.isEventEnabled(AuditEventType.NATIVE_BUY_SUCCESS)) { operation.run(); return; }
        BigInteger before = balance(player);
        ItemStack purchased = items.copy(); // Inventory insertion may consume the supplied stack.
        String merchant = player.containerMenu instanceof ShopMenu menu && menu.getHasMerchant()
                ? menu.getCobbleMerchant().getMerchantUUID().toString() : null;
        operation.run();
        AuditLogService.logNativeBuySuccess(UUID.randomUUID(), change(player, before), List.of(purchased), merchant);
    }

    public static void nativeSell(ServerPlayer player, Runnable operation) {
        if (!AuditLogService.isEventEnabled(AuditEventType.NATIVE_SELL_SUCCESS)
                || !(player.containerMenu instanceof BankMenu menu)) { operation.run(); return; }
        BigInteger before = balance(player);
        SimpleContainer container = menu.getBankContainer();
        List<ItemStack> itemsBefore = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) itemsBefore.add(container.getItem(slot).copy());
        String merchant = menu.getHasMerchant() ? menu.getCobbleMerchant().getMerchantUUID().toString() : null;
        operation.run();
        List<ItemStack> sold = new ArrayList<>();
        for (int slot = 0; slot < itemsBefore.size(); slot++) {
            ItemStack stack = itemsBefore.get(slot);
            if (stack.isEmpty()) continue;
            ItemStack remaining = container.getItem(slot);
            int count = stack.getCount() - (ItemStack.isSameItemSameComponents(stack, remaining) ? remaining.getCount() : 0);
            if (count > 0) sold.add(stack.copyWithCount(count));
        }
        AuditLogService.logNativeSellSuccess(UUID.randomUUID(), change(player, before), sold, merchant);
    }

    private static BigInteger balance(ServerPlayer player) {
        return PlayerExtensionKt.getCobbleDollars(player);
    }

    private static AuditBalanceChange change(ServerPlayer player, BigInteger before) {
        return new AuditBalanceChange(AuditPlayerRef.of(player), before, balance(player));
    }
}
