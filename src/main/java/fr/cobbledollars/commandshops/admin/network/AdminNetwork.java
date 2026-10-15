package fr.cobbledollars.commandshops.admin.network;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.AdminMessages;
import fr.cobbledollars.commandshops.admin.config.AdminConfigService;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import static fr.cobbledollars.commandshops.admin.AdminMessage.translated;

/** Optional admin protocol. No client implementation class is resolved on a dedicated server. */
@EventBusSubscriber(modid = CobbleDollarsCommandShopsMod.MODID)
public final class AdminNetwork {
    private static final String CLIENT_BRIDGE = "fr.cobbledollars.commandshops.client.admin.AdminClientBridge";
    private static final UUID NO_TRANSFER = new UUID(0L, 0L);
    private static final long SESSION_TIMEOUT_MILLIS = 10 * 60_000L;
    private static final int MAX_SESSIONS = 128;
    private static final int MAX_UPLOADS = 8;
    private static final Map<UUID, ServerSession> SESSIONS = new HashMap<>();
    private static UUID clientSession;
    private static AdminTransfer clientTransfer;
    private static UUID clientSave;
    private static long clientSaveStarted;
    private static int cleanupTicks;

    private AdminNetwork() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(AdminRequestPayload.TYPE, AdminRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        open(player);
                    }
                }));
        registrar.playBidirectional(AdminChunkPayload.TYPE, AdminChunkPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.SERVERBOUND && context.player() instanceof ServerPlayer player) {
                        acceptUpload(player, payload);
                    } else if (context.flow() == PacketFlow.CLIENTBOUND && FMLEnvironment.dist.isClient()) {
                        acceptSnapshot(payload);
                    }
                }));
        registrar.playToClient(AdminResultPayload.TYPE, AdminResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) {
                        if (clientSave == null || payload.transferId().equals(clientSave)
                                || payload.transferId().equals(NO_TRANSFER)) {
                            clientSave = null;
                            dispatchResult(payload.success(), AdminMessage.literal(payload.message()));
                        }
                    }
                }));
        registrar.playToClient(AdminLocalizedResultPayload.TYPE, AdminLocalizedResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient() && (clientSave == null || payload.transferId().equals(clientSave)
                            || payload.transferId().equals(NO_TRANSFER))) {
                        clientSave = null;
                        dispatchResult(payload.success(), payload.message());
                    }
                }));
    }

    /** Returns false only when the client does not support the optional admin protocol. */
    public static boolean open(ServerPlayer player) {
        if (!hasChannels(player)) {
            return false;
        }
        if (!checkPermission(player, NO_TRANSFER)) {
            return true;
        }
        long now = System.currentTimeMillis();
        ServerSession session = SESSIONS.get(player.getUUID());
        if (session != null && session.connection == player.connection.getConnection()
                && now - session.lastSnapshot < 1_000L) {
            sendResult(player, NO_TRANSFER, false, translated("network.reload_throttled", "Wait one second before reloading the configuration."));
            return true;
        }
        if (session == null || session.connection != player.connection.getConnection()
                || now - session.lastAccess >= SESSION_TIMEOUT_MILLIS) {
            if (SESSIONS.size() >= MAX_SESSIONS) {
                sendResult(player, NO_TRANSFER, false, translated("network.too_many_sessions", "Too many administration sessions are open. Try again later."));
                return true;
            }
            session = new ServerSession(player.connection.getConnection(), now);
            SESSIONS.put(player.getUUID(), session);
        }
        session.lastAccess = now;
        session.lastSnapshot = now;
        session.upload = null;
        try {
            sendSnapshot(player, session, AdminConfigService.snapshot(player.getServer()));
        } catch (IOException | RuntimeException exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to read admin configuration snapshot", exception);
            sendResult(player, NO_TRANSFER, false, failure("network.load_failed", "Could not load the configuration: %s", exception));
        }
        return true;
    }

    /** Client entry point used by the screen's refresh action. */
    public static void requestSnapshot() {
        if (FMLEnvironment.dist.isClient()) {
            PacketDistributor.sendToServer(new AdminRequestPayload());
        }
    }

    /** Client entry point used by the screen's save action. */
    public static void save(String snapshotJson) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        if (clientSession == null) {
            dispatchResult(false, translated("network.session_expired_open", "Session expired. Reload the configuration with /cdshops admin."));
            return;
        }
        if (clientSave != null) {
            dispatchResult(false, translated("network.save_in_progress", "A save is already in progress."));
            return;
        }
        try {
            byte[] bytes = AdminTransfer.encode(snapshotJson);
            clientSave = UUID.randomUUID();
            clientSaveStarted = System.currentTimeMillis();
            for (int index = 0; index * AdminTransfer.CHUNK_BYTES < bytes.length; index++) {
                PacketDistributor.sendToServer(new AdminChunkPayload(clientSession, clientSave, bytes.length,
                        index, AdminTransfer.chunk(bytes, index)));
            }
        } catch (RuntimeException exception) {
            clientSave = null;
            dispatchResult(false, failure("network.send_failed", "Could not send the configuration: %s", exception));
        }
    }

    private static void acceptUpload(ServerPlayer player, AdminChunkPayload payload) {
        if (!checkPermission(player, payload.transferId())) {
            return;
        }
        long now = System.currentTimeMillis();
        ServerSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.connection != player.connection.getConnection()
                || !session.id.equals(payload.sessionId()) || now - session.lastAccess >= SESSION_TIMEOUT_MILLIS) {
            sendResult(player, payload.transferId(), false, translated("network.session_expired_save", "Session expired. Reload the configuration before saving."));
            return;
        }
        session.lastAccess = now;
        try {
            if (session.upload == null) {
                if (payload.index() != 0) {
                    throw AdminMessages.argument("network.missing_start", "The beginning of the transfer is missing. Try again.");
                }
                if (SESSIONS.values().stream().filter(value -> value.upload != null).count() >= MAX_UPLOADS) {
                    throw AdminMessages.argument("network.too_many_uploads", "Too many transfers are in progress. Try again later.");
                }
                session.upload = new AdminTransfer(payload.transferId(), payload.totalBytes(), now);
            }
            if (!session.upload.accept(payload.transferId(), payload.totalBytes(), payload.index(), payload.data(), now)) {
                return;
            }
            String json = session.upload.text();
            session.upload = null;
            // A second check sits immediately before the write, even after the final chunk's check.
            if (!checkPermission(player, payload.transferId())) {
                return;
            }
            AdminConfigService.SaveResult result = AdminConfigService.save(player.getServer(), json);
            sendResult(player, payload.transferId(), result.success(), result.message());
            if (result.success() && result.snapshotJson() != null) {
                sendSnapshot(player, session, result.snapshotJson());
            }
        } catch (RuntimeException exception) {
            session.upload = null;
            CobbleDollarsCommandShopsMod.LOGGER.warn("Rejected admin configuration upload from {}: {}",
                    player.getGameProfile().getName(), exception.getMessage());
            sendResult(player, payload.transferId(), false, failure("network.save_failed", "Save rejected: %s", exception));
        }
    }

    private static void sendSnapshot(ServerPlayer player, ServerSession session, String json) {
        byte[] bytes = AdminTransfer.encode(json);
        UUID transferId = UUID.randomUUID();
        for (int index = 0; index * AdminTransfer.CHUNK_BYTES < bytes.length; index++) {
            if (!checkPermission(player, transferId)) {
                return;
            }
            PacketDistributor.sendToPlayer(player, new AdminChunkPayload(session.id, transferId, bytes.length,
                    index, AdminTransfer.chunk(bytes, index)));
        }
    }

    private static void acceptSnapshot(AdminChunkPayload payload) {
        long now = System.currentTimeMillis();
        try {
            if (payload.index() == 0) {
                clientTransfer = new AdminTransfer(payload.transferId(), payload.totalBytes(), now);
                clientSession = payload.sessionId();
                clientSave = null;
            }
            if (clientTransfer == null || !payload.sessionId().equals(clientSession)) {
                throw AdminMessages.argument("network.missing_start", "The beginning of the transfer is missing. Try again.");
            }
            if (clientTransfer.accept(payload.transferId(), payload.totalBytes(), payload.index(), payload.data(), now)) {
                String json = clientTransfer.text();
                clientTransfer = null;
                invokeClient("acceptSnapshot", new Class<?>[] {String.class}, json);
            }
        } catch (RuntimeException exception) {
            clientTransfer = null;
            dispatchResult(false, failure("network.receive_failed", "Could not receive the configuration: %s", exception));
        }
    }

    private static boolean checkPermission(ServerPlayer player, UUID transferId) {
        if (player.hasPermissions(2)) {
            return true;
        }
        SESSIONS.remove(player.getUUID());
        sendResult(player, transferId, false, translated("network.permission_required", "Administrator permission level 2 is required."));
        return false;
    }

    private static boolean hasChannels(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, AdminChunkPayload.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, AdminResultPayload.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, AdminRequestPayload.TYPE.id());
    }

    private static void sendResult(ServerPlayer player, UUID transferId, boolean success, AdminMessage message) {
        if (NetworkRegistry.hasChannel(player.connection, AdminLocalizedResultPayload.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new AdminLocalizedResultPayload(transferId, success, message));
        } else if (NetworkRegistry.hasChannel(player.connection, AdminResultPayload.TYPE.id())) {
            PacketDistributor.sendToPlayer(player, new AdminResultPayload(transferId, success, message.english()));
        } else {
            player.sendSystemMessage(message.component());
        }
    }

    private static void dispatchResult(boolean success, AdminMessage message) {
        invokeClient("acceptLocalizedResult", new Class<?>[] {boolean.class, AdminMessage.class}, success, message);
    }

    private static AdminMessage failure(String id, String englishPattern, Throwable exception) {
        return exception instanceof AdminMessages.Failure ? AdminMessages.fromException(exception)
                : translated(id, englishPattern, AdminMessages.fromException(exception).english());
    }

    static void clearClientState() {
        clientSession = null;
        clientTransfer = null;
        clientSave = null;
    }

    static void tickClient() {
        long now = System.currentTimeMillis();
        if (clientTransfer != null && clientTransfer.expired(now)) {
            clientTransfer = null;
            dispatchResult(false, translated("network.receive_timeout", "Receiving the configuration timed out. Reload it."));
        }
        if (clientSave != null && now - clientSaveStarted > AdminTransfer.TIMEOUT_MILLIS + 5_000L) {
            clientSave = null;
            dispatchResult(false, translated("network.response_timeout", "The server did not respond. Reload the configuration to check whether it was saved."));
        }
    }

    private static void invokeClient(String method, Class<?>[] types, Object... args) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            Class.forName(CLIENT_BRIDGE).getMethod(method, types).invoke(null, args);
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to dispatch optional admin UI payload {}", method, cause);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SESSIONS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (++cleanupTicks % 20 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ServerSession>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ServerSession> entry = iterator.next();
            ServerSession session = entry.getValue();
            if (now - session.lastAccess >= SESSION_TIMEOUT_MILLIS || !session.connection.isConnected()) {
                iterator.remove();
            } else if (session.upload != null && session.upload.expired(now)) {
                ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
                UUID transferId = session.upload.id();
                session.upload = null;
                if (player != null && player.connection.getConnection() == session.connection) {
                    sendResult(player, transferId, false, translated("network.upload_timeout", "The transfer timed out. Try saving again."));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        SESSIONS.clear();
        cleanupTicks = 0;
    }

    private static final class ServerSession {
        final UUID id = UUID.randomUUID();
        final Connection connection;
        long lastAccess;
        long lastSnapshot;
        AdminTransfer upload;

        ServerSession(Connection connection, long now) {
            this.connection = connection;
            this.lastAccess = now;
        }
    }
}
