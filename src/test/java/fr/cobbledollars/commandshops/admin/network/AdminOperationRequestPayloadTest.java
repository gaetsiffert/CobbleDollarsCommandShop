package fr.cobbledollars.commandshops.admin.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AdminOperationRequestPayloadTest {
    @Test
    void roundTripsTheRequestIdentityUsedForReloadSnapshotsAndErrors() {
        AdminOperationRequestPayload source = new AdminOperationRequestPayload(UUID.randomUUID());
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            AdminOperationRequestPayload.STREAM_CODEC.encode(buffer, source);
            assertEquals(source, AdminOperationRequestPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
