package fr.cobbledollars.commandshops.shop;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class ShopVisibilityData extends SavedData {
    private static final String DATA_NAME = "cobbledollarscommandshops_shop_visibility";
    private static final String SHOPS_KEY = "shops";
    private static final String MESSAGE_KEY = "message";
    private static final String CHANGED_BY_KEY = "changed_by";
    private static final String CHANGED_AT_KEY = "changed_at_ms";
    private static final Factory<ShopVisibilityData> FACTORY = new Factory<>(ShopVisibilityData::new, ShopVisibilityData::load);

    private final Map<String, DisabledState> disabledShops = new HashMap<>();

    public static ShopVisibilityData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static ShopVisibilityData load(CompoundTag tag, HolderLookup.Provider provider) {
        ShopVisibilityData data = new ShopVisibilityData();
        CompoundTag shopsTag = tag.getCompound(SHOPS_KEY);
        for (String shopId : shopsTag.getAllKeys()) {
            CompoundTag shopTag = shopsTag.getCompound(shopId);
            data.disabledShops.put(shopId, new DisabledState(
                    normalizeMessage(shopTag.contains(MESSAGE_KEY) ? shopTag.getString(MESSAGE_KEY) : null),
                    shopTag.contains(CHANGED_BY_KEY) ? shopTag.getString(CHANGED_BY_KEY) : null,
                    shopTag.getLong(CHANGED_AT_KEY)
            ));
        }
        return data;
    }

    public VisibilityStatus status(String shopId) {
        String normalizedShopId = ShopFiles.normalizeId(shopId, "shop id");
        DisabledState state = disabledShops.get(normalizedShopId);
        if (state == null) {
            return VisibilityStatus.enabledStatus();
        }
        return VisibilityStatus.disabledStatus(state.message(), state.changedBy(), state.changedAtMillis());
    }

    public VisibilityStatus disable(String shopId, String message, String changedBy, long changedAtMillis) {
        String normalizedShopId = ShopFiles.normalizeId(shopId, "shop id");
        disabledShops.put(normalizedShopId, new DisabledState(normalizeMessage(message), changedBy, changedAtMillis));
        setDirty();
        return status(normalizedShopId);
    }

    public VisibilityStatus enable(String shopId) {
        String normalizedShopId = ShopFiles.normalizeId(shopId, "shop id");
        if (disabledShops.remove(normalizedShopId) != null) {
            setDirty();
        }
        return VisibilityStatus.enabledStatus();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag shopsTag = new CompoundTag();
        for (Map.Entry<String, DisabledState> entry : disabledShops.entrySet()) {
            CompoundTag shopTag = new CompoundTag();
            if (entry.getValue().message() != null) {
                shopTag.putString(MESSAGE_KEY, entry.getValue().message());
            }
            if (entry.getValue().changedBy() != null) {
                shopTag.putString(CHANGED_BY_KEY, entry.getValue().changedBy());
            }
            shopTag.putLong(CHANGED_AT_KEY, entry.getValue().changedAtMillis());
            shopsTag.put(entry.getKey(), shopTag);
        }
        tag.put(SHOPS_KEY, shopsTag);
        return tag;
    }

    private static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message;
    }

    private record DisabledState(String message, String changedBy, long changedAtMillis) {
    }

    public record VisibilityStatus(boolean enabled, String message, String changedBy, long changedAtMillis) {
        private static VisibilityStatus enabledStatus() {
            return new VisibilityStatus(true, null, null, 0L);
        }

        private static VisibilityStatus disabledStatus(String message, String changedBy, long changedAtMillis) {
            return new VisibilityStatus(false, message, changedBy, changedAtMillis);
        }

        public Component denialMessage(String shopId) {
            if (enabled) {
                throw new IllegalStateException("Enabled shops do not have a denial message.");
            }
            if (message != null) {
                return Component.literal(message);
            }
            return Component.translatable("cobbledollarscommandshops.feedback.shop_denied.disabled", shopId);
        }
    }
}
