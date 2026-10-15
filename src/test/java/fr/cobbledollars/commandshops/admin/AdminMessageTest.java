package fr.cobbledollars.commandshops.admin;

import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminMessageTest {
    @Test
    void translatedMessagesKeepTheirKeyFallbackAndLiteralArgumentsSeparate() {
        String path = "shops/étoile%/shop.json";
        String diagnostic = "100% 🌟 %s";
        AdminMessage message = AdminMessage.translated("store.example", "Cannot open '%s': %s", path, diagnostic);

        assertEquals(AdminMessage.PREFIX + "store.example", message.key());
        assertEquals("Cannot open '%s': %s", message.fallback());
        assertEquals(List.of(path, diagnostic), message.arguments());
        assertEquals("Cannot open '" + path + "': " + diagnostic, message.english());
        TranslatableContents contents = assertInstanceOf(TranslatableContents.class, message.component().getContents());
        assertEquals(message.key(), contents.getKey());
        assertEquals(message.fallback(), contents.getFallback());
        assertArrayEquals(new Object[]{path, diagnostic}, contents.getArgs());
    }

    @Test
    void literalDiagnosticsPreservePercentSignsAndUnicodeWithoutFormatting() {
        String diagnostic = "Invalid price in étoile: 100% (%s, %d) 🌟";
        AdminMessage message = AdminMessage.literal(diagnostic);

        assertEquals("", message.key());
        assertEquals(List.of(), message.arguments());
        assertEquals(diagnostic, message.english());
        assertEquals(diagnostic, message.component().getString());
    }

    @Test
    void knownExceptionsKeepTheirEnglishDiagnosticAndTranslationMetadata() {
        List<Throwable> failures = List.of(
                AdminMessages.io("store.example", "Invalid file: %s", "100% étoile"),
                AdminMessages.argument("store.example", "Invalid file: %s", "100% étoile"),
                AdminMessages.state("store.example", "Invalid file: %s", "100% étoile"));

        for (Throwable failure : failures) {
            AdminMessage message = AdminMessages.fromException(failure);
            assertEquals("Invalid file: 100% étoile", failure.getMessage());
            assertEquals(failure.getMessage(), message.english());
            assertEquals(AdminMessage.PREFIX + "store.example", message.key());
            assertEquals(List.of("100% étoile"), message.arguments());
        }
    }

    @Test
    void unknownExceptionsKeepEnglishDiagnosticsOrUseTheirClassName() {
        assertEquals(AdminMessage.literal("Invalid price: 100% %s"),
                AdminMessages.fromException(new IOException("Invalid price: 100% %s")));
        assertEquals(AdminMessage.literal("IOException"), AdminMessages.fromException(new IOException()));
        assertEquals(AdminMessage.literal("IllegalStateException"),
                AdminMessages.fromException(new IllegalStateException(" \t")));
    }

    @Test
    void messageArgumentsCannotChangeAfterConstruction() {
        List<String> arguments = new ArrayList<>(List.of("original"));
        AdminMessage message = new AdminMessage(AdminMessage.PREFIX + "store.example", "File: %s", arguments);
        arguments.set(0, "changed");

        assertEquals("File: original", message.english());
        assertThrows(UnsupportedOperationException.class, () -> message.arguments().set(0, "changed"));
    }

    @Test
    void rejectsUnrelatedTranslationKeysAndExcessiveArgumentCounts() {
        assertThrows(IllegalArgumentException.class, () -> new AdminMessage("unrelated.message", "Message", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AdminMessage(
                AdminMessage.PREFIX + "x".repeat(AdminMessage.MAX_KEY_LENGTH), "Message", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AdminMessage(AdminMessage.PREFIX + "store.example",
                "Message", Collections.nCopies(AdminMessage.MAX_ARGUMENTS + 1, "argument")));
    }

    @Test
    void boundsDiagnosticTextAndEachArgumentBeforeNetworkEncoding() {
        String oversized = "é".repeat(AdminMessage.MAX_TEXT_LENGTH + 1);
        AdminMessage literal = AdminMessage.literal(oversized);
        AdminMessage translated = AdminMessage.translated("store.example", "File: %s", oversized);

        assertEquals("é".repeat(AdminMessage.MAX_TEXT_LENGTH), literal.fallback());
        assertEquals("é".repeat(AdminMessage.MAX_TEXT_LENGTH), translated.arguments().getFirst());
    }

    @Test
    void textLimitsKeepSupplementaryUnicodeCharactersWhole() {
        String beforeBoundary = "x".repeat(AdminMessage.MAX_TEXT_LENGTH - 1);
        String splitPair = beforeBoundary + "🌟suffix";
        String completePair = "x".repeat(AdminMessage.MAX_TEXT_LENGTH - 2) + "🌟";

        assertEquals(beforeBoundary, AdminMessage.literal(splitPair).fallback());
        assertEquals(List.of(beforeBoundary), AdminMessage.translated("store.example", "File: %s", splitPair).arguments());
        assertEquals(completePair, AdminMessage.literal(completePair + "suffix").fallback());
    }
}
