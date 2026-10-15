package fr.cobbledollars.commandshops.admin.network;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import fr.cobbledollars.commandshops.admin.AdminMessages;

/** A bounded, ordered UTF-8 transfer, independent of Minecraft and its packet size limits. */
final class AdminTransfer {
    static final int CHUNK_BYTES = 24 * 1024;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final long TIMEOUT_MILLIS = 30_000L;

    private final UUID id;
    private final byte[] bytes;
    private final long expiresAt;
    private int nextIndex;
    private int received;

    AdminTransfer(UUID id, int totalBytes, long now) {
        if (totalBytes <= 0 || totalBytes > MAX_BYTES) {
            throw AdminMessages.argument("transfer.size", "The configuration must contain between 1 byte and 8 MiB.");
        }
        this.id = id;
        this.bytes = new byte[totalBytes];
        this.expiresAt = now + TIMEOUT_MILLIS;
    }

    boolean accept(UUID transferId, int totalBytes, int index, byte[] chunk, long now) {
        if (expired(now)) {
            throw AdminMessages.argument("transfer.expired", "The transfer timed out. Try again.");
        }
        if (!id.equals(transferId) || totalBytes != bytes.length || index != nextIndex) {
            throw AdminMessages.argument("transfer.order", "The transfer is incomplete or its chunks are out of order.");
        }
        int expected = Math.min(CHUNK_BYTES, bytes.length - received);
        if (expected <= 0 || chunk.length != expected) {
            throw AdminMessages.argument("transfer.chunk_size", "The configuration chunk size is invalid.");
        }
        System.arraycopy(chunk, 0, bytes, received, chunk.length);
        received += chunk.length;
        nextIndex++;
        return received == bytes.length;
    }

    boolean expired(long now) {
        return now >= expiresAt;
    }

    UUID id() {
        return id;
    }

    String text() {
        if (received != bytes.length) {
            throw AdminMessages.state("transfer.incomplete", "The configuration is incomplete.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            IllegalArgumentException failure = AdminMessages.argument("transfer.invalid_utf8", "The configuration is not valid UTF-8 text.");
            failure.initCause(exception);
            throw failure;
        }
    }

    static byte[] encode(String text) {
        // Reject oversized strings before creating their UTF-8 representation.
        if (text.length() > MAX_BYTES) {
            throw AdminMessages.argument("transfer.too_large", "The configuration exceeds the 8 MiB limit.");
        }
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException exception) {
            IllegalArgumentException failure = AdminMessages.argument("transfer.invalid_unicode", "The configuration contains an invalid Unicode character.");
            failure.initCause(exception);
            throw failure;
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw AdminMessages.argument("transfer.size", "The configuration must contain between 1 byte and 8 MiB.");
        }
        return bytes;
    }

    static byte[] chunk(byte[] bytes, int index) {
        int start = Math.multiplyExact(index, CHUNK_BYTES);
        return Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + CHUNK_BYTES));
    }
}
