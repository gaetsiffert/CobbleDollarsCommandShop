package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Optional correlated reload. The original empty request remains wire-compatible for older clients. */
public record AdminOperationRequestPayload(UUID operationId) implements CustomPacketPayload {
    public static final Type<AdminOperationRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_operation_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminOperationRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminOperationRequestPayload::write, AdminOperationRequestPayload::new);

    private AdminOperationRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(operationId);
    }

    @Override
    public Type<AdminOperationRequestPayload> type() {
        return TYPE;
    }
}
