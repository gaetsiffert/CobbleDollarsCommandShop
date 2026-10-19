package fr.cobbledollars.commandshops.admin.network;

import java.util.Objects;
import java.util.UUID;
import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.audit.AuditQuery;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Separate optional channel: old configuration/stock codecs keep their original wire format. */
public record AdminAuditRequestPayload(UUID sessionId, UUID operationId, String query, boolean refresh) implements CustomPacketPayload {
    public static final Type<AdminAuditRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_audit_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminAuditRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminAuditRequestPayload::write, AdminAuditRequestPayload::new);
    public AdminAuditRequestPayload {
        Objects.requireNonNull(sessionId); Objects.requireNonNull(operationId); Objects.requireNonNull(query);
        if (operationId.equals(new UUID(0,0)) || query.length() > 4096) throw new IllegalArgumentException("Invalid audit request.");
        AuditQuery.parse(query);
    }
    private AdminAuditRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUUID(), buffer.readUtf(4096), buffer.readBoolean());
    }
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId); buffer.writeUUID(operationId); buffer.writeUtf(query,4096); buffer.writeBoolean(refresh);
    }
    @Override public Type<AdminAuditRequestPayload> type() { return TYPE; }
}
