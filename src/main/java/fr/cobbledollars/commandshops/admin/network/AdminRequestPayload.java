package fr.cobbledollars.commandshops.admin.network;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AdminRequestPayload() implements CustomPacketPayload {
    public static final Type<AdminRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new AdminRequestPayload());

    @Override
    public Type<AdminRequestPayload> type() {
        return TYPE;
    }
}
