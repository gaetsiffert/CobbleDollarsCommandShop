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

    public record OfferState(
            int categoryIndex,
            int offerIndex,
            int stock,
            long nextRestockAtMillis,
            int nextRestockAmount,
            String restockZoneId,
            List<BonusState> bonuses
    ) {
        public OfferState {
            bonuses = List.copyOf(bonuses);
        }

        private OfferState(RegistryFriendlyByteBuf buffer) {
            this(
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    ByteBufCodecs.VAR_LONG.decode(buffer),
                    ByteBufCodecs.VAR_INT.decode(buffer),
                    decodeZoneId(buffer),
                    readBonuses(buffer)
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            ByteBufCodecs.VAR_INT.encode(buffer, categoryIndex);
            ByteBufCodecs.VAR_INT.encode(buffer, offerIndex);
            ByteBufCodecs.VAR_INT.encode(buffer, stock);
            ByteBufCodecs.VAR_LONG.encode(buffer, nextRestockAtMillis);
            ByteBufCodecs.VAR_INT.encode(buffer, nextRestockAmount);
            ByteBufCodecs.STRING_UTF8.encode(buffer, restockZoneId == null ? "" : restockZoneId);
            writeBonuses(buffer, bonuses);
        }

        public boolean hasNextRestock() {
            return nextRestockAtMillis >= 0L;
        }

        private static String decodeZoneId(RegistryFriendlyByteBuf buffer) {
            String zoneId = ByteBufCodecs.STRING_UTF8.decode(buffer);
            return zoneId.isEmpty() ? null : zoneId;
        }

        private static List<BonusState> readBonuses(RegistryFriendlyByteBuf buffer) {
            int size = ByteBufCodecs.VAR_INT.decode(buffer);
            ArrayList<BonusState> bonuses = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                bonuses.add(new BonusState(buffer));
            }
            return bonuses;
        }

        private static void writeBonuses(RegistryFriendlyByteBuf buffer, List<BonusState> bonuses) {
            ByteBufCodecs.VAR_INT.encode(buffer, bonuses.size());
            for (BonusState bonus : bonuses) {
                bonus.write(buffer);
            }
        }
    }

    public record BonusState(int requiredBundles, List<RewardState> rewards) {
        public BonusState {
            rewards = List.copyOf(rewards);
        }

        private BonusState(RegistryFriendlyByteBuf buffer) {
            this(ByteBufCodecs.VAR_INT.decode(buffer), readRewards(buffer));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            ByteBufCodecs.VAR_INT.encode(buffer, requiredBundles);
            ByteBufCodecs.VAR_INT.encode(buffer, rewards.size());
            for (RewardState reward : rewards) {
                reward.write(buffer);
            }
        }

        private static List<RewardState> readRewards(RegistryFriendlyByteBuf buffer) {
            int size = ByteBufCodecs.VAR_INT.decode(buffer);
            ArrayList<RewardState> rewards = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                rewards.add(new RewardState(buffer));
            }
            return rewards;
        }
    }

    public record RewardState(net.minecraft.world.item.ItemStack stack) {
        public RewardState {
            stack = stack.copy();
        }

        private RewardState(RegistryFriendlyByteBuf buffer) {
            this(net.minecraft.world.item.ItemStack.STREAM_CODEC.decode(buffer));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(buffer, stack);
        }
    }
}
