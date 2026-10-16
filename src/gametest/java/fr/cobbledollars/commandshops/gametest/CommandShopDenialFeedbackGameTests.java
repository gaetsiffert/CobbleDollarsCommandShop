package fr.cobbledollars.commandshops.gametest;

import java.io.IOException;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import fr.cobbledollars.commandshops.feedback.ShopFeedbackService;
import fr.cobbledollars.commandshops.network.payload.ClientOverlayMessagePayload;
import fr.cobbledollars.commandshops.shop.CommandShopSessions;
import fr.cobbledollars.commandshops.shop.ShopVisibilityData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "feedback_denial.", groups = "feedback", side = Dist.DEDICATED_SERVER)
public final class CommandShopDenialFeedbackGameTests {
    private static final String SHOP_ID = "general_store";

    private CommandShopDenialFeedbackGameTests() {
    }

    @TestHolder(value = "suppressed_npc_command_notifies_denied_target", title = "Suppressed NPC commands notify denied players")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 80, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void suppressed_npc_command_notifies_denied_target(ExtendedGameTestHelper helper) throws IOException {
        verifyDisabledOpening(helper, true);
    }

    @TestHolder(value = "self_command_notifies_denied_player", title = "Player commands deliver disabled-shop feedback directly")
    @GameTest(batch = "feedback.delivery", timeoutTicks = 80, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void self_command_notifies_denied_player(ExtendedGameTestHelper helper) throws IOException {
        verifyDisabledOpening(helper, false);
    }

    private static void verifyDisabledOpening(ExtendedGameTestHelper helper, boolean fromNpc) throws IOException {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        Entity npc = fromNpc ? createNpc(helper) : null;
        ShopVisibilityData visibility = ShopVisibilityData.get(helper.getLevel().getServer());
        ShopVisibilityData.VisibilityStatus previousVisibility = visibility.status(SHOP_ID);
        String reason = fromNpc ? "NPC maintenance denial" : "Player maintenance denial";
        boolean waitingForQueuedOpenCheck = false;
        try {
            ShopFeedbackService.clear(); // Default shop_denied explicitly requests chat and sound.
            visibility.disable(SHOP_ID, reason, "feedback-regression", System.currentTimeMillis());
            if (npc != null) {
                npc.moveTo(player.position());
                helper.getLevel().addFreshEntity(npc);
            }
            CommandSourceStack source = npc == null
                    ? player.createCommandSourceStack().withPermission(4)
                    : npc.createCommandSourceStack().withPermission(2).withSuppressedOutput();
            String command = "cdshops open " + SHOP_ID + (fromNpc ? " @p[distance=..1]" : "");
            player.clearOutboundPackets();

            int result;
            try {
                result = helper.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
            } catch (CommandSyntaxException exception) {
                helper.fail("A disabled shop must notify its target directly, including a suppressed NPC source, instead of throwing: "
                        + exception.getMessage());
                return;
            }
            helper.assertValueEqual(0, result, "A disabled shop must report an unsuccessful opening.");
            var overlays = player.getOutboundPayloads(ClientOverlayMessagePayload.class).toList();
            helper.assertValueEqual(1, overlays.size(), "The target must receive one custom denial notification.");
            helper.assertValueEqual(reason, overlays.getFirst().message().getString(), "The target notification must use the disabled-shop reason.");
            var chats = player.getOutboundPackets(ClientboundSystemChatPacket.class).toList();
            helper.assertValueEqual(1, chats.size(), "Configured chat feedback must also reach the target, with no command error or success duplicate.");
            helper.assertTrue(!chats.getFirst().overlay(), "The default denial channel must remain chat.");
            helper.assertValueEqual(reason, chats.getFirst().content().getString(), "Chat must carry the same denial reason.");
            helper.assertTrue(player.containerMenu == player.inventoryMenu, "A denied opening must not replace the player's menu.");

            // Re-enable the shop in finally: an accidentally queued opening would now succeed.
            helper.runAtTickTime(6, () -> {
                try {
                    helper.assertTrue(player.containerMenu == player.inventoryMenu, "A denied command must not leave a deferred opening queued.");
                    helper.succeed();
                } finally {
                    CommandShopSessions.cleanupPlayer(player.getUUID());
                }
            });
            waitingForQueuedOpenCheck = true;
        } finally {
            if (previousVisibility.enabled()) {
                visibility.enable(SHOP_ID);
            } else {
                visibility.disable(SHOP_ID, previousVisibility.message(), previousVisibility.changedBy(), previousVisibility.changedAtMillis());
            }
            ShopFeedbackService.reload();
            if (npc != null) {
                npc.discard();
            }
            if (!waitingForQueuedOpenCheck) {
                CommandShopSessions.cleanupPlayer(player.getUUID());
            }
        }
    }

    private static Entity createNpc(ExtendedGameTestHelper helper) {
        // Use an installed NPC implementation when present; a living entity source exercises the same suppressed-command contract otherwise.
        for (String namespace : new String[] { "customnpcs", "easy_npc" }) {
            for (var id : BuiltInRegistries.ENTITY_TYPE.keySet().stream().filter(id -> id.getNamespace().equals(namespace)).sorted().toList()) {
                Entity entity = BuiltInRegistries.ENTITY_TYPE.get(id).create(helper.getLevel());
                if (entity instanceof LivingEntity) {
                    return entity;
                }
                if (entity != null) {
                    entity.discard();
                }
            }
        }
        return EntityType.PIG.create(helper.getLevel());
    }
}
