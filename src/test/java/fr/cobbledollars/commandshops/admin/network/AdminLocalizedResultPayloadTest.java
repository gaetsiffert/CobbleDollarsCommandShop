package fr.cobbledollars.commandshops.admin.network;

import fr.cobbledollars.commandshops.admin.AdminMessage;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminLocalizedResultPayloadTest {
    @Test
    void preservesTranslatedMessageMetadataAndUnicodeArgumentsAcrossTheNetwork() {
        AdminMessage message = AdminMessage.translated("store.example", "Invalid file '%s': %s",
                "shops/étoile%/shop.json", "Price: 100% 🌟 %s");
        AdminLocalizedResultPayload source = new AdminLocalizedResultPayload(UUID.randomUUID(), false, message);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AdminLocalizedResultPayload.STREAM_CODEC.encode(buffer, source);
            AdminLocalizedResultPayload decoded = AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer);

            assertEquals(source, decoded);
            assertEquals("Invalid file 'shops/étoile%/shop.json': Price: 100% 🌟 %s", decoded.message().english());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void preservesLiteralDiagnosticsWithoutInterpretingTheirPercentSigns() {
        AdminMessage message = AdminMessage.literal("Invalid price: 100% %s 🌟");
        AdminLocalizedResultPayload source = new AdminLocalizedResultPayload(UUID.randomUUID(), false, message);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AdminLocalizedResultPayload.STREAM_CODEC.encode(buffer, source);
            assertEquals(source, AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void truncatesLongUnicodeDiagnosticsWithoutCorruptingTheirNetworkEncoding() {
        String expectedArgument = "x".repeat(AdminMessage.MAX_TEXT_LENGTH - 1);
        AdminMessage message = AdminMessage.translated("store.example", "File: %s", expectedArgument + "🌟suffix");
        AdminLocalizedResultPayload source = new AdminLocalizedResultPayload(UUID.randomUUID(), false, message);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AdminLocalizedResultPayload.STREAM_CODEC.encode(buffer, source);
            AdminLocalizedResultPayload decoded = AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer);

            assertEquals(source, decoded);
            assertEquals("File: " + expectedArgument, decoded.message().english());
        } finally {
            buffer.release();
        }
    }

    @Test
    void acceptsMaximumKeyTextAndArgumentCountIncludingMultibyteCharacters() {
        String key = AdminMessage.PREFIX + "x".repeat(AdminMessage.MAX_KEY_LENGTH - AdminMessage.PREFIX.length());
        AdminMessage message = new AdminMessage(key, "é".repeat(AdminMessage.MAX_TEXT_LENGTH),
                Collections.nCopies(AdminMessage.MAX_ARGUMENTS, "界".repeat(AdminMessage.MAX_TEXT_LENGTH)));
        AdminLocalizedResultPayload source = new AdminLocalizedResultPayload(UUID.randomUUID(), true, message);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AdminLocalizedResultPayload.STREAM_CODEC.encode(buffer, source);
            assertEquals(source, AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsNegativeOrExcessiveArgumentCountsBeforeReadingArguments() {
        for (int count : new int[]{-1, AdminMessage.MAX_ARGUMENTS + 1, Integer.MAX_VALUE}) {
            RegistryFriendlyByteBuf buffer = header();
            try {
                buffer.writeUtf(AdminMessage.PREFIX + "store.example");
                buffer.writeUtf("Message");
                buffer.writeVarInt(count);

                assertThrows(IllegalArgumentException.class, () -> AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void rejectsTranslationKeysOutsideTheAdministrativeNamespace() {
        RegistryFriendlyByteBuf buffer = header();
        try {
            buffer.writeUtf("unrelated.message");
            buffer.writeUtf("Message");
            buffer.writeVarInt(0);

            assertThrows(IllegalArgumentException.class, () -> AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsOversizedKeyFallbackOrArgumentOnTheWire() {
        for (int field = 0; field < 3; field++) {
            RegistryFriendlyByteBuf buffer = header();
            try {
                buffer.writeUtf(field == 0 ? "x".repeat(AdminMessage.MAX_KEY_LENGTH + 1)
                        : AdminMessage.PREFIX + "store.example");
                buffer.writeUtf(field == 1 ? "x".repeat(AdminMessage.MAX_TEXT_LENGTH + 1) : "Message");
                buffer.writeVarInt(1);
                buffer.writeUtf(field == 2 ? "x".repeat(AdminMessage.MAX_TEXT_LENGTH + 1) : "argument");

                assertThrows(DecoderException.class, () -> AdminLocalizedResultPayload.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void originalResultPayloadStillCarriesAnEnglishFormattedDiagnostic() {
        AdminMessage message = AdminMessage.translated("store.example", "Invalid file: %s", "étoile 100%");
        AdminResultPayload source = new AdminResultPayload(UUID.randomUUID(), false, message.english());
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            AdminResultPayload.STREAM_CODEC.encode(buffer, source);
            AdminResultPayload decoded = AdminResultPayload.STREAM_CODEC.decode(buffer);

            assertEquals(source, decoded);
            assertEquals("Invalid file: étoile 100%", decoded.message());
        } finally {
            buffer.release();
        }
    }

    private static RegistryFriendlyByteBuf header() {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeUUID(UUID.randomUUID());
        buffer.writeBoolean(false);
        return buffer;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
