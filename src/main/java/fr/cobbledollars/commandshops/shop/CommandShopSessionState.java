package fr.cobbledollars.commandshops.shop;

import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

final class CommandShopSessionState {
    private final String shopId;
    private final UUID sessionUuid;
    private final int containerId;
    private long nextRefreshTick;
    private ResourceLocation lastDimensionId;
    private ShopSessionSnapshot lastSnapshot;

    CommandShopSessionState(String shopId, UUID sessionUuid, int containerId) {
        this.shopId = shopId;
        this.sessionUuid = sessionUuid;
        this.containerId = containerId;
        this.nextRefreshTick = Long.MAX_VALUE;
        this.lastDimensionId = null;
        this.lastSnapshot = null;
    }

    String shopId() {
        return shopId;
    }

    UUID sessionUuid() {
        return sessionUuid;
    }

    int containerId() {
        return containerId;
    }

    long nextRefreshTick() {
        return nextRefreshTick;
    }

    void setNextRefreshTick(long nextRefreshTick) {
        this.nextRefreshTick = nextRefreshTick;
    }

    ResourceLocation lastDimensionId() {
        return lastDimensionId;
    }

    void setLastDimensionId(ResourceLocation lastDimensionId) {
        this.lastDimensionId = lastDimensionId;
    }

    ShopSessionSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    void setLastSnapshot(ShopSessionSnapshot lastSnapshot) {
        this.lastSnapshot = lastSnapshot;
    }
}
