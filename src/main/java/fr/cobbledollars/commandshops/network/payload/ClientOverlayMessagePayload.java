package fr.cobbledollars.commandshops.network.payload;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientOverlayMessagePayload(String message, int color, int ttlMillis) implements CustomPacketPayload {
    public static final Type<ClientOverlayMessagePayload> TYPE = new Type<>(ResourceLocation.parse(CobbleDollarsCommandShopsMod.MODID + ":client_overlay_message"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientOverlayMessagePayload> STREAM_CODEC =
            CustomPacketPayload.codec(ClientOverlayMessagePayload::write, ClientOverlayMessagePayload::new);

    private ClientOverlayMessagePayload(RegistryFriendlyByteBuf buffer) {
        this(
                ByteBufCodecs.STRING_UTF8.decode(buffer),
                ByteBufCodecs.INT.decode(buffer),
                ByteBufCodecs.VAR_INT.decode(buffer)
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        ByteBufCodecs.STRING_UTF8.encode(buffer, message);
        ByteBufCodecs.INT.encode(buffer, color);
        ByteBufCodecs.VAR_INT.encode(buffer, ttlMillis);
    }

    @Override
    public Type<ClientOverlayMessagePayload> type() {
        return TYPE;
    }
}
