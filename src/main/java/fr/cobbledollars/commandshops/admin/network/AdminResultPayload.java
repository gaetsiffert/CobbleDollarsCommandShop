package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AdminResultPayload(UUID transferId, boolean success, String message) implements CustomPacketPayload {
    public static final Type<AdminResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminResultPayload::write, AdminResultPayload::new);

    public AdminResultPayload {
        if (message.length() > 2048) {
            message = message.substring(0, 2048);
        }
    }

    private AdminResultPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readBoolean(), buffer.readUtf(2048));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(transferId);
        buffer.writeBoolean(success);
        buffer.writeUtf(message, 2048);
    }

    @Override
    public Type<AdminResultPayload> type() {
        return TYPE;
    }
}
