package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Runtime state responses cannot be confused with editable configuration snapshots. */
public record AdminLiveChunkPayload(UUID sessionId, UUID operationId, int totalBytes, int index, byte[] data)
        implements CustomPacketPayload {
    public static final Type<AdminLiveChunkPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_live_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminLiveChunkPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminLiveChunkPayload::write, AdminLiveChunkPayload::new);

    private AdminLiveChunkPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray(AdminTransfer.CHUNK_BYTES));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
        buffer.writeUUID(operationId);
        buffer.writeVarInt(totalBytes);
        buffer.writeVarInt(index);
        buffer.writeByteArray(data);
    }

    @Override public Type<AdminLiveChunkPayload> type() { return TYPE; }
}
