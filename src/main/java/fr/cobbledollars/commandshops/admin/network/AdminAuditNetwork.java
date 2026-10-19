package fr.cobbledollars.commandshops.admin.network;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import fr.cobbledollars.commandshops.admin.AdminMessages;
import fr.cobbledollars.commandshops.audit.AuditQuery;
import fr.cobbledollars.commandshops.audit.AuditQueryService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import static fr.cobbledollars.commandshops.admin.AdminMessage.translated;

/** One bounded worker reads files. Permission, connection and session are rechecked before delivery. */
public final class AdminAuditNetwork {
    private static final Map<UUID, Job> JOBS = new HashMap<>(); // server thread only
    private static ThreadPoolExecutor executor;
    private static AuditQueryService reader;
    private record Job(AdminAuditRequestPayload request, FutureTask<Void> task, long startedAt) { }
    private static UUID clientOperation, clientSession;
    private static long clientStarted;
    private static AdminTransfer clientTransfer;
    private AdminAuditNetwork() { }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(AdminAuditRequestPayload.TYPE, AdminAuditRequestPayload.STREAM_CODEC,
                (payload,context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) execute(player,payload);
                }));
        registrar.playToClient(AdminAuditChunkPayload.TYPE, AdminAuditChunkPayload.STREAM_CODEC,
                (payload,context) -> context.enqueueWork(() -> { if (FMLEnvironment.dist.isClient()) accept(payload); }));
    }

    private static void execute(ServerPlayer player, AdminAuditRequestPayload request) {
        if (!NetworkRegistry.hasChannel(player.connection, AdminAuditChunkPayload.TYPE.id())) return;
        AdminMessage invalid = AdminNetwork.acceptLiveOperation(player,request.sessionId(),request.operationId());
        if (invalid != null) { send(player, request, new AdminLiveEnvelope(false,null,invalid)); return; }
        if (JOBS.containsKey(player.getUUID())) {
            send(player,request,new AdminLiveEnvelope(false,null,translated("audit.busy","An audit request is already running. Try again shortly.")));
            return;
        }
        if (executor == null) {
            reader = new AuditQueryService();
            executor = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable,"commandshops-audit-reader"); thread.setDaemon(true); return thread;
            });
        }
        MinecraftServer server = player.getServer();
        AuditQueryService currentReader = reader;
        FutureTask<Void> task = new FutureTask<>(() -> {
            AdminLiveEnvelope outcome;
            try {
                outcome = new AdminLiveEnvelope(true,currentReader.query(AuditQuery.parse(request.query()),request.refresh()),
                        translated("audit.loaded","Audit data loaded."));
            } catch (Exception failure) {
                outcome = new AdminLiveEnvelope(false,null,translated("audit.failed","Could not read the audit: %s",AdminMessages.fromException(failure).english()));
            }
            AdminLiveEnvelope result = outcome;
            try { server.execute(() -> complete(player,request,result)); }
            catch (RejectedExecutionException stopped) { /* Server is shutting down. */ }
            return null;
        });
        JOBS.put(player.getUUID(),new Job(request,task,System.currentTimeMillis()));
        try { executor.execute(task); }
        catch (RejectedExecutionException busy) {
            JOBS.remove(player.getUUID());
            send(player,request,new AdminLiveEnvelope(false,null,translated("audit.busy","An audit request is already running. Try again shortly.")));
        }
    }

    private static void complete(ServerPlayer player, AdminAuditRequestPayload request, AdminLiveEnvelope result) {
        Job job = JOBS.get(player.getUUID());
        if (job == null || job.request() != request) return;
        JOBS.remove(player.getUUID());
        if (player.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || AdminNetwork.validateLiveSession(player,request.sessionId()) != null) return;
        try { send(player,request,result); }
        catch (RuntimeException tooLarge) {
            send(player,request,new AdminLiveEnvelope(false,null,translated("audit.failed","Could not read the audit: %s",AdminMessages.fromException(tooLarge).english())));
        }
    }

    private static void send(ServerPlayer player, AdminAuditRequestPayload request, AdminLiveEnvelope response) {
        byte[] bytes = AdminTransfer.encode(response.json());
        for (int index=0; index*AdminTransfer.CHUNK_BYTES<bytes.length; index++) {
            PacketDistributor.sendToPlayer(player,new AdminAuditChunkPayload(request.sessionId(),request.operationId(),bytes.length,index,AdminTransfer.chunk(bytes,index)));
        }
    }

    static void cancel(UUID player) {
        Job job = JOBS.remove(player);
        if (job != null) {
            job.task().cancel(true);
            if (executor != null) executor.remove(job.task());
        }
    }
    static void tickServer() {
        long now = System.currentTimeMillis();
        var jobs = JOBS.entrySet().iterator();
        while (jobs.hasNext()) {
            Job job = jobs.next().getValue();
            if (now - job.startedAt() > AdminTransfer.TIMEOUT_MILLIS) {
                job.task().cancel(true);
                if (executor != null) executor.remove(job.task());
                jobs.remove();
            }
        }
    }
    static void shutdown() {
        JOBS.values().forEach(job -> job.task().cancel(true)); JOBS.clear();
        if (executor != null) executor.shutdownNow();
        executor = null; reader = null;
    }

    public static void request(AdminAuditRequestPayload request) {
        if (!FMLEnvironment.dist.isClient()) return;
        clientOperation=request.operationId(); clientSession=request.sessionId(); clientStarted=System.currentTimeMillis(); clientTransfer=null;
        PacketDistributor.sendToServer(request);
    }
    private static void accept(AdminAuditChunkPayload payload) {
        if (!payload.operationId().equals(clientOperation) || !payload.sessionId().equals(clientSession)
                || !payload.sessionId().equals(AdminNetwork.clientSessionId())) return;
        try {
            if (clientTransfer == null) {
                if (payload.index()!=0) throw new IllegalArgumentException("Missing audit transfer start.");
                clientTransfer=new AdminTransfer(payload.operationId(),payload.totalBytes(),System.currentTimeMillis());
            }
            if (clientTransfer.accept(payload.operationId(),payload.totalBytes(),payload.index(),payload.data(),System.currentTimeMillis())) {
                String json=clientTransfer.text(); AdminLiveEnvelope.parse(json);
                UUID id=clientOperation; clearClientState(); dispatch(id,json);
            }
        } catch (RuntimeException error) {
            UUID id=clientOperation; clearClientState();
            dispatch(id,new AdminLiveEnvelope(false,null,translated("audit.failed","Could not read the audit: %s",AdminMessages.fromException(error).english())).json());
        }
    }
    static void tickClient() {
        if (clientOperation!=null && System.currentTimeMillis()-clientStarted>AdminTransfer.TIMEOUT_MILLIS) {
            UUID id=clientOperation; clearClientState();
            dispatch(id,new AdminLiveEnvelope(false,null,translated("audit.timeout","The audit request timed out. Refresh to try again.")).json());
        }
    }
    public static void clearClientState() { clientOperation=null; clientSession=null; clientTransfer=null; }
    private static void dispatch(UUID id,String json) {
        if (!FMLEnvironment.dist.isClient()) return;
        try { Class.forName("fr.cobbledollars.commandshops.client.admin.AdminAuditClient").getMethod("acceptResult",UUID.class,String.class).invoke(null,id,json); }
        catch (ReflectiveOperationException error) {
            Throwable cause=error instanceof InvocationTargetException e ? e.getCause() : error;
            CobbleDollarsCommandShopsMod.LOGGER.error("Failed to dispatch audit reply",cause);
        }
    }
}
