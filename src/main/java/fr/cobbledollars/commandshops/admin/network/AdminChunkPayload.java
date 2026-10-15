package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Used in both directions, with a connection-bound administration session nonce. */
public record AdminChunkPayload(UUID sessionId, UUID transferId, int totalBytes, int index, byte[] data)
        implements CustomPacketPayload {
    public static final Type<AdminChunkPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminChunkPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminChunkPayload::write, AdminChunkPayload::new);

    private AdminChunkPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readByteArray(AdminTransfer.CHUNK_BYTES));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
        buffer.writeUUID(transferId);
        buffer.writeVarInt(totalBytes);
        buffer.writeVarInt(index);
        buffer.writeByteArray(data);
    }

    @Override
    public Type<AdminChunkPayload> type() {
        return TYPE;
    }
}
