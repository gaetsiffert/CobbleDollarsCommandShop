package fr.cobbledollars.commandshops.network.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ShopUiStatePayload(UUID sessionUuid, List<OfferState> offers) implements CustomPacketPayload {
    public static final Type<ShopUiStatePayload> TYPE = new Type<>(ResourceLocation.parse(CobbleDollarsCommandShopsMod.MODID + ":shop_ui_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopUiStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(ShopUiStatePayload::write, ShopUiStatePayload::new);

    public ShopUiStatePayload {
        offers = List.copyOf(offers);
    }

    private ShopUiStatePayload(RegistryFriendlyByteBuf buffer) {
        this(UUIDUtil.STREAM_CODEC.decode(buffer), readOffers(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        UUIDUtil.STREAM_CODEC.encode(buffer, sessionUuid);
        ByteBufCodecs.VAR_INT.encode(buffer, offers.size());
        for (OfferState offer : offers) {
            offer.write(buffer);
        }
    }

    @Override
    public Type<ShopUiStatePayload> type() {
        return TYPE;
    }

    private static List<OfferState> readOffers(RegistryFriendlyByteBuf buffer) {
        int size = ByteBufCodecs.VAR_INT.decode(buffer);
        ArrayList<OfferState> offers = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            offers.add(new OfferState(buffer));
        }
        return offers;
    }

    public record OfferState(int categoryIndex, int offerIndex, int stock, long nextRestockAtMillis, int nextRestockAmount, String restockZoneId) {
        private OfferState(RegistryFriendlyByteBuf buffer) {
            this(
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_LONG.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    decodeZoneId(buffer)
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            ByteBufCodecs.VAR_INT.encode(buffer, categoryIndex);
            ByteBufCodecs.VAR_INT.encode(buffer, offerIndex);
            ByteBufCodecs.VAR_INT.encode(buffer, stock);
            ByteBufCodecs.VAR_LONG.encode(buffer, nextRestockAtMillis);
            ByteBufCodecs.VAR_INT.encode(buffer, nextRestockAmount);
            ByteBufCodecs.STRING_UTF8.encode(buffer, restockZoneId == null ? "" : restockZoneId);
        }

        public boolean hasNextRestock() {
            return nextRestockAtMillis >= 0L;
        }

        private static String decodeZoneId(RegistryFriendlyByteBuf buffer) {
            String zoneId = ByteBufCodecs.STRING_UTF8.decode(buffer);
            return zoneId.isEmpty() ? null : zoneId;
        }
    }
}
