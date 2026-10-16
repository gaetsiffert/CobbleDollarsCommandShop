package fr.cobbledollars.commandshops.admin.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminLiveRequestPayloadTest {
    @Test
    void roundTripsTargetAndMutationIdentityWithoutUsingConfigurationUploads() {
        AdminLiveRequestPayload source = request("set_stock", "custom", "offer", "variant", 27, false, "Fermé", true);
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            AdminLiveRequestPayload.STREAM_CODEC.encode(buffer, source);
            assertEquals(source, AdminLiveRequestPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
            assertTrue(source.mutation());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsUnknownActionsInvalidStockAndOversizedArgumentsBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> request("delete", "shop", "", "", 0, true, "", false));
        assertThrows(IllegalArgumentException.class, () -> request("set_stock", "shop", "", "", -1, true, "", false));
        assertThrows(IllegalArgumentException.class, () -> request("stock", "x".repeat(257), "", "", 0, true, "", false));
        assertThrows(IllegalArgumentException.class, () -> request("visibility", "shop", "", "", 0, true, "x".repeat(2049), false));
        assertThrows(IllegalArgumentException.class, () -> new AdminLiveRequestPayload(UUID.randomUUID(), new UUID(0, 0),
                "stock", "shop", "", "", "", 0, true, "", false));
    }

    @Test
    void distinguishesReadsFromImmediateMutations() {
        assertFalse(request("overview", "", "", "", 0, true, "", false).mutation());
        assertFalse(request("stock", "shop", "", "", 0, true, "", false).mutation());
        assertTrue(request("visibility", "shop", "", "", 0, true, "", false).mutation());
        assertTrue(request("restock_offer", "shop", "offer", "", 0, true, "", false).mutation());
        assertTrue(request("restock_shop", "shop", "", "", 0, true, "", true).mutation());
    }

    private static AdminLiveRequestPayload request(String action, String shop, String offer, String stock,
            int amount, boolean enabled, String message, boolean allPlayers) {
        return new AdminLiveRequestPayload(UUID.randomUUID(), UUID.randomUUID(), action, shop, "player", offer,
                stock, amount, enabled, message, allPlayers);
    }
}
