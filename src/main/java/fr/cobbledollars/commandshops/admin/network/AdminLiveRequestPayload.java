package fr.cobbledollars.commandshops.admin.network;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import fr.cobbledollars.commandshops.CobbleDollarsCommandShopsMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small immediate runtime operation, deliberately separate from configuration uploads. */
public record AdminLiveRequestPayload(UUID sessionId, UUID operationId, String action, String shopId,
        String playerId, String offerId, String stockKey, int amount, boolean enabled, String message,
        boolean allPlayers) implements CustomPacketPayload {
    public static final int MAX_ID = 256;
    public static final int MAX_PLAYER = 64;
    public static final int MAX_STOCK_KEY = 1024;
    public static final int MAX_MESSAGE = 2048;
    private static final Set<String> ACTIONS = Set.of("overview", "stock", "visibility", "set_stock",
            "restock_offer", "restock_shop");
    public static final Type<AdminLiveRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            CobbleDollarsCommandShopsMod.MODID, "admin_live_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AdminLiveRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(AdminLiveRequestPayload::write, AdminLiveRequestPayload::new);

    public AdminLiveRequestPayload {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(operationId);
        if (operationId.equals(new UUID(0L, 0L)) || !ACTIONS.contains(action))
            throw new IllegalArgumentException("Invalid live administration operation.");
        bounded(shopId, MAX_ID);
        bounded(playerId, MAX_PLAYER);
        bounded(offerId, MAX_ID);
        bounded(stockKey, MAX_STOCK_KEY);
        bounded(message, MAX_MESSAGE);
        if (amount < 0) throw new IllegalArgumentException("Stock must not be negative.");
    }

    private AdminLiveRequestPayload(RegistryFriendlyByteBuf buffer) {
        this(buffer.readUUID(), buffer.readUUID(), buffer.readUtf(32), buffer.readUtf(MAX_ID),
                buffer.readUtf(MAX_PLAYER), buffer.readUtf(MAX_ID), buffer.readUtf(MAX_STOCK_KEY),
                buffer.readVarInt(), buffer.readBoolean(), buffer.readUtf(MAX_MESSAGE), buffer.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(sessionId);
        buffer.writeUUID(operationId);
        buffer.writeUtf(action, 32);
        buffer.writeUtf(shopId, MAX_ID);
        buffer.writeUtf(playerId, MAX_PLAYER);
        buffer.writeUtf(offerId, MAX_ID);
        buffer.writeUtf(stockKey, MAX_STOCK_KEY);
        buffer.writeVarInt(amount);
        buffer.writeBoolean(enabled);
        buffer.writeUtf(message, MAX_MESSAGE);
        buffer.writeBoolean(allPlayers);
    }

    public boolean mutation() {
        return !action.equals("overview") && !action.equals("stock");
    }

    private static void bounded(String value, int maximum) {
        if (Objects.requireNonNull(value).length() > maximum)
            throw new IllegalArgumentException("Live administration argument is too long.");
    }

    @Override public Type<AdminLiveRequestPayload> type() { return TYPE; }
}
