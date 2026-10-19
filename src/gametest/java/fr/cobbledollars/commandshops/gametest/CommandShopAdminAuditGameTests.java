package fr.cobbledollars.commandshops.gametest;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import fr.cobbledollars.commandshops.admin.network.*;
import fr.cobbledollars.commandshops.audit.AuditLogService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.GameTestPlayer;

@ForEachTest(idPrefix = "admin_audit.", groups = "admin_audit", side = Dist.DEDICATED_SERVER)
public final class CommandShopAdminAuditGameTests {
    private CommandShopAdminAuditGameTests() { }

    @TestHolder(value = "async_query_is_correlated_and_read_only", title = "Audit queries deliver correlated data off the server thread")
    @GameTest(batch = "admin_audit.read", timeoutTicks = 300, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void async_query_is_correlated_and_read_only(ExtendedGameTestHelper helper) {
        GameTestPlayer player = admin(helper);
        UUID session = session(player, helper);
        String shop = "audit_network_" + player.getUUID();
        AuditLogService.logVisibilityChanged(shop, false, "Audit fixture", player.getGameProfile().getName(), player.getUUID().toString());
        UUID operation = UUID.randomUUID();
        AdminAuditRequestPayload request = new AdminAuditRequestPayload(session, operation,
                "{\"view\":\"journal\",\"shop\":\"" + shop + "\"}", true);
        execute(player, request);
        helper.assertTrue(player.getOutboundPayloads(AdminAuditChunkPayload.class).noneMatch(p -> p.operationId().equals(operation)),
                "A disk query must not run inline on the server tick.");
        UUID busy = UUID.randomUUID();
        execute(player, new AdminAuditRequestPayload(session, busy, "{}", false));
        helper.assertTrue(!response(player, busy).success(), "One player must not queue simultaneous scans.");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                player.getOutboundPayloads(AdminAuditChunkPayload.class).anyMatch(p -> p.operationId().equals(operation)), "Waiting for the audit worker"))
                .thenExecute(() -> {
                    var result = response(player, operation);
                    helper.assertTrue(result.success(), "Audit reader failed: " + result.message().english());
                    var rows = result.state().getAsJsonArray("rows");
                    helper.assertValueEqual(1, rows.size(), "The shop filter should isolate the fixture.");
                    helper.assertValueEqual(shop, rows.get(0).getAsJsonObject().get("shop_id").getAsString(), "Wrong recorded shop.");
                    helper.assertTrue(!rows.get(0).getAsJsonObject().get("enabled").getAsBoolean(), "Recorded visibility should be available.");
                    player.clearOutboundPackets();
                    execute(player, request);
                }).thenWaitUntil(() -> helper.assertTrue(
                        player.getOutboundPayloads(AdminAuditChunkPayload.class).anyMatch(p -> p.operationId().equals(operation)), "Waiting for the replay rejection"))
                .thenExecute(() -> helper.assertTrue(!response(player, operation).success(), "A completed operation cannot be replayed."))
                .thenSucceed();
    }

    @TestHolder(value = "permission_and_nonce_protect_history", title = "Audit history requires operator permission and the correct session")
    @GameTest(batch = "admin_audit.permissions", timeoutTicks = 100, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void permission_and_nonce_protect_history(ExtendedGameTestHelper helper) {
        GameTestPlayer player = admin(helper);
        UUID session = session(player, helper);
        UUID wrong = UUID.randomUUID();
        execute(player, new AdminAuditRequestPayload(UUID.randomUUID(), wrong, "{}", false));
        helper.assertTrue(!response(player, wrong).success() && response(player, wrong).state() == null,
                "A forged session must never receive audit data.");
        player.getServer().getPlayerList().deop(player.getGameProfile());
        UUID untrusted = UUID.randomUUID();
        execute(player, new AdminAuditRequestPayload(session, untrusted, "{}", false));
        helper.assertTrue(!response(player, untrusted).success() && response(player, untrusted).state() == null,
                "A deopped administrator must never receive audit data.");
        helper.succeed();
    }

    @TestHolder(value = "permission_revocation_drops_pending_reply", title = "Audit worker rechecks permission before delivery")
    @GameTest(batch = "admin_audit.revoke", timeoutTicks = 300, setupTicks = 1)
    @EmptyTemplate(value = "5x4x5", floor = true)
    public static void permission_revocation_drops_pending_reply(ExtendedGameTestHelper helper) {
        GameTestPlayer player = admin(helper);
        UUID session = session(player, helper), operation = UUID.randomUUID();
        execute(player, new AdminAuditRequestPayload(session, operation, "{}", true));
        player.getServer().getPlayerList().deop(player.getGameProfile());
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!hasJob(player.getUUID()), "Waiting for the pending job to finish"))
                .thenExecute(() -> helper.assertTrue(player.getOutboundPayloads(AdminAuditChunkPayload.class)
                        .noneMatch(p -> p.operationId().equals(operation)), "Revoked permissions must suppress a previously requested result."))
                .thenSucceed();
    }

    private static GameTestPlayer admin(ExtendedGameTestHelper helper) {
        GameTestPlayer player = helper.makeTickingMockServerPlayerInCorner(GameType.SURVIVAL);
        player.getServer().getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 2, false));
        helper.addEndListener(passed -> player.getServer().getPlayerList().deop(player.getGameProfile()));
        return player;
    }
    private static UUID session(GameTestPlayer player, ExtendedGameTestHelper helper) {
        helper.assertTrue(AdminNetwork.open(player), "The mock connection must support admin packets.");
        UUID session = player.getOutboundPayloads(AdminChunkPayload.class).findFirst().orElseThrow().sessionId();
        player.clearOutboundPackets();
        return session;
    }
    private static void execute(ServerPlayer player, AdminAuditRequestPayload request) {
        try {
            Method method = AdminAuditNetwork.class.getDeclaredMethod("execute", ServerPlayer.class, AdminAuditRequestPayload.class);
            method.setAccessible(true); method.invoke(null, player, request);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static boolean hasJob(UUID player) {
        try {
            var field = AdminAuditNetwork.class.getDeclaredField("JOBS"); field.setAccessible(true);
            return ((java.util.Map<?,?>)field.get(null)).containsKey(player);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static AdminLiveEnvelope response(GameTestPlayer player, UUID operation) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        player.getOutboundPayloads(AdminAuditChunkPayload.class).filter(p -> p.operationId().equals(operation))
                .sorted(Comparator.comparingInt(AdminAuditChunkPayload::index)).forEach(p -> bytes.writeBytes(p.data()));
        return AdminLiveEnvelope.parse(bytes.toString(StandardCharsets.UTF_8));
    }
}
