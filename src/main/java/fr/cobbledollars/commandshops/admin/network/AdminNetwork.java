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
    private static final AdminClientSession CLIENT_SESSION = new AdminClientSession();
    private static final Map<UUID, IncomingSnapshot> CLIENT_TRANSFERS = new HashMap<>();
    private static boolean clientCorrelated;
    private static boolean clientLegacySnapshotAccepted;
    private static boolean clientConnected;
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
        registrar.playToServer(AdminOperationRequestPayload.TYPE, AdminOperationRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player && !payload.operationId().equals(NO_TRANSFER)) {
                        open(player, payload.operationId());
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
                        acceptResult(payload.transferId(), payload.success(), AdminMessage.literal(payload.message()));
                    }
                }));
        registrar.playToClient(AdminLocalizedResultPayload.TYPE, AdminLocalizedResultPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) acceptResult(payload.transferId(), payload.success(), payload.message());
                }));
        AdminLiveNetwork.register(registrar);
        AdminAuditNetwork.register(registrar);
    }

    /** Returns false only when the client does not support the optional admin protocol. */
    public static boolean open(ServerPlayer player) {
        return open(player, NO_TRANSFER);
    }

    private static boolean open(ServerPlayer player, UUID operationId) {
        if (!hasChannels(player)) {
            return false;
        }
        if (!checkPermission(player, operationId)) {
            return true;
        }
        long now = System.currentTimeMillis();
        ServerSession session = SESSIONS.get(player.getUUID());
        if (session != null && session.connection == player.connection.getConnection()
                && now - session.lastSnapshot < 1_000L) {
            sendResult(player, operationId, false, translated("network.reload_throttled", "Wait one second before reloading the configuration."));
            return true;
        }
        if (session == null || session.connection != player.connection.getConnection()
                || now - session.lastAccess >= SESSION_TIMEOUT_MILLIS) {
            if (SESSIONS.size() >= MAX_SESSIONS) {
                sendResult(player, operationId, false, translated("network.too_many_sessions", "Too many administration sessions are open. Try again later."));
                return true;
            }
            session = new ServerSession(player.connection.getConnection(), now);
            SESSIONS.put(player.getUUID(), session);
        }
        session.lastAccess = now;
        session.lastSnapshot = now;
        session.upload = null;
        try {
            sendSnapshot(player, session, operationId, AdminConfigService.snapshot(player.getServer()));
        } catch (IOException | RuntimeException exception) {
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to read admin configuration snapshot", exception);
            sendResult(player, operationId, false, failure("network.load_failed", "Could not load the configuration: %s", exception));
        }
        return true;
    }

    /** Client entry point used by the screen's refresh action. */
    public static void requestSnapshot() {
        if (FMLEnvironment.dist.isClient()) {
            PacketDistributor.sendToServer(new AdminRequestPayload());
        }
    }

    public static void requestSnapshot(UUID operationId) {
        if (!FMLEnvironment.dist.isClient()) return;
        if (!clientCorrelated) {
            dispatchResult(operationId, false, translated("network.correlated_required", "Update the server addon before editing configuration safely."));
            return;
        }
        CLIENT_SESSION.requested(operationId);
        PacketDistributor.sendToServer(new AdminOperationRequestPayload(operationId));
    }

    /** Client entry point used by the screen's save action. */
    public static void save(String snapshotJson) {
        save(snapshotJson, UUID.randomUUID());
    }

    public static void save(String snapshotJson, UUID operationId) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        if (CLIENT_SESSION.id() == null) {
            dispatchResult(operationId, false, translated("network.session_expired_open", "Session expired. Reload the configuration with /cdshops admin."));
            return;
        }
        if (!clientCorrelated) {
            dispatchResult(operationId, false, translated("network.correlated_required", "Update the server addon before editing configuration safely."));
            return;
        }
        if (clientSave != null) {
            dispatchResult(operationId, false, translated("network.save_in_progress", "A save is already in progress."));
            return;
        }
        try {
            byte[] bytes = AdminTransfer.encode(snapshotJson);
            CLIENT_SESSION.requested(operationId);
            clientSave = operationId;
            clientSaveStarted = System.currentTimeMillis();
            for (int index = 0; index * AdminTransfer.CHUNK_BYTES < bytes.length; index++) {
                PacketDistributor.sendToServer(new AdminChunkPayload(CLIENT_SESSION.id(), clientSave, bytes.length,
                        index, AdminTransfer.chunk(bytes, index)));
            }
        } catch (RuntimeException exception) {
            clientSave = null;
            dispatchResult(operationId, false, failure("network.send_failed", "Could not send the configuration: %s", exception));
        }
    }

    private static void acceptUpload(ServerPlayer player, AdminChunkPayload payload) {
        if (!checkPermission(player, payload.transferId())) {
            return;
        }
        if (payload.transferId().equals(NO_TRANSFER)) {
            sendResult(player, NO_TRANSFER, false, translated("transfer.order", "The transfer is incomplete or its chunks are out of order."));
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
            AdminConfigService.SaveResult result = AdminConfigService.save(player.getServer(), json, player);
            sendResult(player, payload.transferId(), result.success(), result.message());
            if (result.success() && result.snapshotJson() != null) {
                sendSnapshot(player, session, payload.transferId(), result.snapshotJson());
            }
        } catch (RuntimeException exception) {
            session.upload = null;
            CobbleDollarsCommandShopsMod.LOGGER.warn("Rejected admin configuration upload from {}: {}",
                    player.getGameProfile().getName(), exception.getMessage());
            sendResult(player, payload.transferId(), false, failure("network.save_failed", "Save rejected: %s", exception));
        }
    }

    private static void sendSnapshot(ServerPlayer player, ServerSession session, UUID transferId, String json) {
        byte[] bytes = AdminTransfer.encode(json);
        for (int index = 0; index * AdminTransfer.CHUNK_BYTES < bytes.length; index++) {
            if (!checkPermission(player, transferId)) {
                return;
            }
            PacketDistributor.sendToPlayer(player, new AdminChunkPayload(session.id, transferId, bytes.length,
                    index, AdminTransfer.chunk(bytes, index)));
        }
    }

    private static void acceptSnapshot(AdminChunkPayload payload) {
        if (!clientConnected) return;
        long now = System.currentTimeMillis();
        try {
            if (payload.index() == 0) {
                if (CLIENT_TRANSFERS.size() >= MAX_UPLOADS) {
                    throw AdminMessages.argument("network.too_many_uploads", "Too many transfers are in progress. Try again later.");
                }
                CLIENT_TRANSFERS.put(payload.transferId(), new IncomingSnapshot(payload.sessionId(),
                        new AdminTransfer(payload.transferId(), payload.totalBytes(), now)));
            }
            IncomingSnapshot incoming = CLIENT_TRANSFERS.get(payload.transferId());
            if (incoming == null || !payload.sessionId().equals(incoming.sessionId)) {
                throw AdminMessages.argument("network.missing_start", "The beginning of the transfer is missing. Try again.");
            }
            if (incoming.transfer.accept(payload.transferId(), payload.totalBytes(), payload.index(), payload.data(), now)) {
                String json = incoming.transfer.text();
                CLIENT_TRANSFERS.remove(payload.transferId());
                CLIENT_SESSION.acceptSnapshot(payload.sessionId(), payload.transferId());
                if (payload.transferId().equals(NO_TRANSFER)) clientCorrelated = true;
                if (clientCorrelated) {
                    invokeClient("acceptSnapshot", new Class<?>[] {UUID.class, String.class}, payload.transferId(), json);
                } else if (!clientLegacySnapshotAccepted) {
                    // Read-only compatibility with older servers: unknown ids must never replace a later draft.
                    clientLegacySnapshotAccepted = true;
                    CLIENT_SESSION.acceptSnapshot(payload.sessionId(), NO_TRANSFER);
                    invokeClient("acceptSnapshot", new Class<?>[] {String.class}, json);
                }
            }
        } catch (RuntimeException exception) {
            CLIENT_TRANSFERS.remove(payload.transferId());
            dispatchResult(payload.transferId(), false, failure("network.receive_failed", "Could not receive the configuration: %s", exception));
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

    /** The live protocol shares only the permission-checked session nonce, never configuration state. */
    static AdminMessage validateLiveSession(ServerPlayer player, UUID sessionId) {
        if (!player.hasPermissions(2)) {
            SESSIONS.remove(player.getUUID());
            return translated("network.permission_required", "Administrator permission level 2 is required.");
        }
        long now = System.currentTimeMillis();
        ServerSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.connection != player.connection.getConnection()
                || !session.id.equals(sessionId) || now - session.lastAccess >= SESSION_TIMEOUT_MILLIS) {
            return translated("live.session_expired", "Session expired. Reopen /cdshops admin.");
        }
        session.lastAccess = now;
        return null;
    }

    static AdminMessage acceptLiveOperation(ServerPlayer player, UUID sessionId, UUID operationId) {
        AdminMessage invalid = validateLiveSession(player, sessionId);
        if (invalid != null) return invalid;
        AdminLiveOperations.Acceptance accepted = SESSIONS.get(player.getUUID()).liveOperations
                .accept(operationId, System.currentTimeMillis());
        return switch (accepted) {
            case ACCEPT -> null;
            case DUPLICATE -> translated("live.duplicate", "This operation has already been received. Refresh the live state.");
            case THROTTLED -> translated("live.throttled", "Too many operations. Wait a moment and try again.");
            case EXHAUSTED -> {
                SESSIONS.remove(player.getUUID());
                yield translated("live.session_expired", "Session expired. Reopen /cdshops admin.");
            }
        };
    }

    /** Physical client only: a runtime request may use the current editor's established nonce. */
    public static UUID clientSessionId() {
        return FMLEnvironment.dist.isClient() && clientConnected && clientCorrelated ? CLIENT_SESSION.id() : null;
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

    private static void acceptResult(UUID operationId, boolean success, AdminMessage message) {
        if (!clientConnected) return;
        if (operationId.equals(clientSave)) clientSave = null;
        dispatchResult(operationId, success, message);
    }

    private static void dispatchResult(UUID operationId, boolean success, AdminMessage message) {
        invokeClient("acceptLocalizedResult", new Class<?>[] {UUID.class, boolean.class, AdminMessage.class}, operationId, success, message);
    }

    /** Local UI diagnostics have no pending network operation (also exercised by the client smoke test). */
    private static void dispatchResult(boolean success, AdminMessage message) {
        invokeClient("acceptLocalizedResult", new Class<?>[] {boolean.class, AdminMessage.class}, success, message);
    }

    private static AdminMessage failure(String id, String englishPattern, Throwable exception) {
        return exception instanceof AdminMessages.Failure ? AdminMessages.fromException(exception)
                : translated(id, englishPattern, AdminMessages.fromException(exception).english());
    }

    static void clearClientState() {
        clientConnected = false;
        CLIENT_SESSION.clear();
        CLIENT_TRANSFERS.clear();
        clientCorrelated = false;
        clientLegacySnapshotAccepted = false;
        clientSave = null;
        AdminLiveNetwork.clearClientState();
        AdminAuditNetwork.clearClientState();
        invokeClient("clear", new Class<?>[0]);
    }

    static void beginClientConnection() {
        clearClientState();
        clientConnected = true;
    }

    static void tickClient() {
        AdminLiveNetwork.tickClient();
        AdminAuditNetwork.tickClient();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, IncomingSnapshot>> incoming = CLIENT_TRANSFERS.entrySet().iterator();
        while (incoming.hasNext()) {
            Map.Entry<UUID, IncomingSnapshot> entry = incoming.next();
            if (entry.getValue().transfer.expired(now)) {
                incoming.remove();
                dispatchTimeout(entry.getKey(), translated("network.receive_timeout", "Receiving the configuration timed out. Reload it."));
            }
        }
        if (clientSave != null && now - clientSaveStarted > AdminTransfer.TIMEOUT_MILLIS + 5_000L) {
            UUID operationId = clientSave;
            clientSave = null;
            dispatchTimeout(operationId, translated("network.response_timeout", "The server did not respond. Reload the configuration to check whether it was saved."));
        }
    }

    private static void dispatchTimeout(UUID operationId, AdminMessage message) {
        invokeClient("acceptTimeout", new Class<?>[] {UUID.class, AdminMessage.class}, operationId, message);
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
            AdminAuditNetwork.cancel(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (++cleanupTicks % 20 != 0) {
            return;
        }
        AdminAuditNetwork.tickServer();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ServerSession>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ServerSession> entry = iterator.next();
            ServerSession session = entry.getValue();
            if (now - session.lastAccess >= SESSION_TIMEOUT_MILLIS || !session.connection.isConnected()) {
                iterator.remove();
                AdminAuditNetwork.cancel(entry.getKey());
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
        AdminAuditNetwork.shutdown();
        SESSIONS.clear();
        cleanupTicks = 0;
    }

    private static final class ServerSession {
        final UUID id = UUID.randomUUID();
        final Connection connection;
        long lastAccess;
        long lastSnapshot;
        AdminTransfer upload;
        final AdminLiveOperations liveOperations = new AdminLiveOperations();

        ServerSession(Connection connection, long now) {
            this.connection = connection;
            this.lastAccess = now;
        }
    }

    private record IncomingSnapshot(UUID sessionId, AdminTransfer transfer) { }
}
