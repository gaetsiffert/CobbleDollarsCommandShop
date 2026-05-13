package fr.cobbledollars.commandshops.network.payload;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import fr.harmex.cobbledollars.common.world.item.trading.shop.Offer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record BankUiStatePayload(List<Entry> exactOffers, List<Entry> genericOffers) implements CustomPacketPayload {
    public static final Type<BankUiStatePayload> TYPE = new Type<>(ResourceLocation.parse(CobbleDollarsCommandShopsMod.MODID + ":bank_ui_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankUiStatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(BankUiStatePayload::write, BankUiStatePayload::new);

    public BankUiStatePayload {
        exactOffers = List.copyOf(exactOffers);
        genericOffers = List.copyOf(genericOffers);
    }

    private BankUiStatePayload(RegistryFriendlyByteBuf buffer) {
        this(readEntries(buffer), readEntries(buffer));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        writeEntries(buffer, exactOffers);
        writeEntries(buffer, genericOffers);
    }

    @Override
    public Type<BankUiStatePayload> type() {
        return TYPE;
    }

    private static List<Entry> readEntries(RegistryFriendlyByteBuf buffer) {
        int size = ByteBufCodecs.VAR_INT.decode(buffer);
        ArrayList<Entry> entries = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            entries.add(new Entry(buffer));
        }
        return entries;
    }

    private static void writeEntries(RegistryFriendlyByteBuf buffer, List<Entry> entries) {
        ByteBufCodecs.VAR_INT.encode(buffer, entries.size());
        for (Entry entry : entries) {
            entry.write(buffer);
        }
    }

    public static BankUiStatePayload fromOffers(List<Offer> exactOffers, List<Offer> genericOffers) {
        ArrayList<Entry> exactEntries = new ArrayList<>(exactOffers.size());
        for (Offer offer : exactOffers) {
            exactEntries.add(Entry.fromOffer(offer));
        }
        ArrayList<Entry> genericEntries = new ArrayList<>(genericOffers.size());
        for (Offer offer : genericOffers) {
            genericEntries.add(Entry.fromOffer(offer));
        }
        return new BankUiStatePayload(exactEntries, genericEntries);
    }

    public record Entry(ItemStack stack, BigInteger price) {
        public Entry {
            stack = stack.copy();
        }

        private Entry(RegistryFriendlyByteBuf buffer) {
            this(ItemStack.STREAM_CODEC.decode(buffer), new BigInteger(ByteBufCodecs.STRING_UTF8.decode(buffer)));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            ItemStack.STREAM_CODEC.encode(buffer, stack);
            ByteBufCodecs.STRING_UTF8.encode(buffer, price.toString());
        }

        private static Entry fromOffer(Offer offer) {
            return new Entry(offer.getItem().copy(), offer.getPrice());
        }
    }
}
