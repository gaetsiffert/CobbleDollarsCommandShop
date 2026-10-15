package fr.cobbledollars.commandshops.admin.network;

import java.util.ArrayList;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.cobbledollars.commandshops.admin.AdminMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Optional extension: older clients continue receiving the original English string result. */
public record AdminLocalizedResultPayload(UUID transferId, boolean success, AdminMessage message) implements CustomPacketPayload {
    public static final Type<AdminLocalizedResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_localized_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminLocalizedResultPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminLocalizedResultPayload::write, AdminLocalizedResultPayload::new);

    private AdminLocalizedResultPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readBoolean(), readMessage(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(transferId);
        buffer.writeBoolean(success);
        buffer.writeUtf(message.key(), AdminMessage.MAX_KEY_LENGTH);
        buffer.writeUtf(message.fallback(), AdminMessage.MAX_TEXT_LENGTH);
        buffer.writeVarInt(message.arguments().size());
        for (String argument : message.arguments()) buffer.writeUtf(argument, AdminMessage.MAX_TEXT_LENGTH);
    }

    private static AdminMessage readMessage(RegistryFriendlyByteBuf buffer) {
        String key = buffer.readUtf(AdminMessage.MAX_KEY_LENGTH);
        String fallback = buffer.readUtf(AdminMessage.MAX_TEXT_LENGTH);
        int count = buffer.readVarInt();
        if (count < 0 || count > AdminMessage.MAX_ARGUMENTS) throw new IllegalArgumentException("Too many message arguments.");
        ArrayList<String> arguments = new ArrayList<>(count);
        for (int index = 0; index < count; index++) arguments.add(buffer.readUtf(AdminMessage.MAX_TEXT_LENGTH));
        return new AdminMessage(key, fallback, arguments);
    }

    @Override public Type<AdminLocalizedResultPayload> type() { return TYPE; }
}
