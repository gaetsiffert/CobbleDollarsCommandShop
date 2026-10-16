package fr.cobbledollars.commandshops.admin.network;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import com.google.gson.JsonObject;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.AdminMessages;
import fr.cobbledollars.commandshops.admin.runtime.AdminLiveService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import static fr.cobbledollars.commandshops.admin.AdminMessage.translated;

/** Optional immediate runtime protocol. It cannot replace the configuration editor's draft. */
public final class AdminLiveNetwork {
    private static final String CLIENT = "fr.cobbledollars.commandshops.client.admin.AdminLiveClient";
    private static UUID clientOperation;
    private static UUID clientSession;
    private static long clientStarted;
    private static AdminTransfer clientTransfer;

    private AdminLiveNetwork() { }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(AdminLiveRequestPayload.TYPE, AdminLiveRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) execute(player, payload);
                }));
        registrar.playToClient(AdminLiveChunkPayload.TYPE, AdminLiveChunkPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) accept(payload);
                }));
    }

    private static void execute(ServerPlayer player, AdminLiveRequestPayload request) {
        if (!NetworkRegistry.hasChannel(player.connection, AdminLiveChunkPayload.TYPE.id())) return;
        AdminMessage rejected = AdminNetwork.acceptLiveOperation(player, request.sessionId(), request.operationId());
        if (rejected != null) {
            send(player, request, new AdminLiveEnvelope(false, null, rejected));
            return;
        }
        JsonObject state;
        try {
            // All mutations and their authoritative refreshed state execute on the server thread.
            state = AdminLiveService.execute(player, request.action(), request.shopId(), request.playerId(),
                    request.offerId(), request.stockKey(), request.amount(), request.enabled(), request.message(),
                    request.allPlayers());
        } catch (RuntimeException exception) {
            CobbleDollarsCommandShopsMod.LOGGER.warn("Rejected live administration operation {} from {}: {}",
                    request.action(), player.getGameProfile().getName(), exception.getMessage());
            AdminMessage message = exception instanceof AdminMessages.Failure ? AdminMessages.fromException(exception)
                    : translated("live.failed", "Operation rejected: %s", AdminMessages.fromException(exception).english());
            send(player, request, new AdminLiveEnvelope(false, null, message));
            return;
        }
        try {
            send(player, request, new AdminLiveEnvelope(true, state, request.mutation()
                    ? translated("live.updated", "Live state updated.") : translated("live.loaded", "Live state loaded.")));
        } catch (RuntimeException exception) {
            CobbleDollarsCommandShopsMod.LOGGER.warn("Could not send live administration state after {}", request.action(), exception);
            send(player, request, new AdminLiveEnvelope(false, null, translated("live.response_failed",
                    "The operation completed, but its state could not be sent. Refresh the live state before making another change.")));
        }
    }

    private static void send(ServerPlayer player, AdminLiveRequestPayload request, AdminLiveEnvelope response) {
        byte[] bytes = AdminTransfer.encode(response.json());
        for (int index = 0; index * AdminTransfer.CHUNK_BYTES < bytes.length; index++) {
            if (response.state() != null && AdminNetwork.validateLiveSession(player, request.sessionId()) != null) return;
            PacketDistributor.sendToPlayer(player, new AdminLiveChunkPayload(request.sessionId(), request.operationId(),
                    bytes.length, index, AdminTransfer.chunk(bytes, index)));
        }
    }

    /** Called by the physical-client helper after it verifies optional channel negotiation. */
    public static void request(AdminLiveRequestPayload request) {
        if (!FMLEnvironment.dist.isClient()) return;
        clientOperation = request.operationId();
        clientSession = request.sessionId();
        clientStarted = System.currentTimeMillis();
        clientTransfer = null;
        PacketDistributor.sendToServer(request);
    }

    private static void accept(AdminLiveChunkPayload payload) {
        if (!payload.operationId().equals(clientOperation) || !payload.sessionId().equals(clientSession)
                || !payload.sessionId().equals(AdminNetwork.clientSessionId())) return;
        try {
            long now = System.currentTimeMillis();
            if (clientTransfer == null) {
                if (payload.index() != 0) throw AdminMessages.argument("network.missing_start", "The beginning of the transfer is missing. Try again.");
                clientTransfer = new AdminTransfer(payload.operationId(), payload.totalBytes(), now);
            }
            if (clientTransfer.accept(payload.operationId(), payload.totalBytes(), payload.index(), payload.data(), now)) {
                String json = clientTransfer.text();
                // Validate the envelope before handing it to a screen. There is no configuration snapshot path here.
                AdminLiveEnvelope.parse(json);
                UUID operationId = clientOperation;
                clearClientState();
                dispatch(operationId, json);
            }
        } catch (RuntimeException exception) {
            UUID operationId = clientOperation;
            clearClientState();
            dispatch(operationId, new AdminLiveEnvelope(false, null,
                    translated("live.failed", "Operation rejected: %s", AdminMessages.fromException(exception).english())).json());
        }
    }

    static void tickClient() {
        if (clientOperation != null && System.currentTimeMillis() - clientStarted > AdminTransfer.TIMEOUT_MILLIS) {
            UUID operationId = clientOperation;
            clearClientState();
            dispatch(operationId, new AdminLiveEnvelope(false, null, translated("live.timeout",
                    "The server did not respond. Refresh the live state before trying another change.")).json());
        }
    }

    public static void clearClientState() {
        clientOperation = null;
        clientSession = null;
        clientTransfer = null;
    }

    private static void dispatch(UUID operationId, String json) {
        if (!FMLEnvironment.dist.isClient()) return;
        try {
            Class.forName(CLIENT).getMethod("acceptResult", UUID.class, String.class).invoke(null, operationId, json);
        } catch (ReflectiveOperationException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to dispatch live administration response", cause);
        }
    }
}
