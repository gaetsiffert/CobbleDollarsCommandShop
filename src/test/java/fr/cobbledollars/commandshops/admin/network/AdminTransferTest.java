package fr.cobbledollars.commandshops.admin.network;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminTransferTest {
    @Test
    void roundTripsLargeUnicodeConfigurationAcrossUtf8Boundaries() {
        String source = "{\"files\":{\"étoile\":\"" + "🌟À€".repeat(40_000) + "\"}}";
        byte[] bytes = AdminTransfer.encode(source);
        UUID id = UUID.randomUUID();
        AdminTransfer transfer = new AdminTransfer(id, bytes.length, 1_000L);

        for (int index = 0; index * AdminTransfer.CHUNK_BYTES < bytes.length; index++) {
            boolean complete = transfer.accept(id, bytes.length, index, AdminTransfer.chunk(bytes, index), 1_001L);
            assertEquals((index + 1) * AdminTransfer.CHUNK_BYTES >= bytes.length, complete);
        }

        assertEquals(source, transfer.text());
    }

    @Test
    void rejectsOversizedAllocationAndEncoding() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new AdminTransfer(id, 0, 0L));
        assertThrows(IllegalArgumentException.class, () -> new AdminTransfer(id, AdminTransfer.MAX_BYTES + 1, 0L));
        assertThrows(IllegalArgumentException.class, () -> AdminTransfer.encode("é".repeat(AdminTransfer.MAX_BYTES / 2 + 1)));
    }

    @Test
    void rejectsWrongOrderDuplicateAndChangedMetadata() {
        byte[] bytes = new byte[AdminTransfer.CHUNK_BYTES + 1];
        UUID id = UUID.randomUUID();
        AdminTransfer transfer = new AdminTransfer(id, bytes.length, 0L);
        assertThrows(IllegalArgumentException.class,
                () -> transfer.accept(id, bytes.length, 1, new byte[1], 1L));
        assertThrows(IllegalArgumentException.class,
                () -> transfer.accept(UUID.randomUUID(), bytes.length, 0, AdminTransfer.chunk(bytes, 0), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> transfer.accept(id, bytes.length - 1, 0, AdminTransfer.chunk(bytes, 0), 1L));
        assertFalse(transfer.accept(id, bytes.length, 0, AdminTransfer.chunk(bytes, 0), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> transfer.accept(id, bytes.length, 0, AdminTransfer.chunk(bytes, 0), 1L));
        assertTrue(transfer.accept(id, bytes.length, 1, new byte[1], 1L));
    }

    @Test
    void rejectsTruncatedOrExtraFragmentsAndIncompleteText() {
        UUID id = UUID.randomUUID();
        AdminTransfer transfer = new AdminTransfer(id, 2, 0L);
        assertThrows(IllegalStateException.class, transfer::text);
        assertThrows(IllegalArgumentException.class, () -> transfer.accept(id, 2, 0, new byte[1], 1L));
        assertTrue(transfer.accept(id, 2, 0, "{}".getBytes(StandardCharsets.UTF_8), 1L));
        assertThrows(IllegalArgumentException.class, () -> transfer.accept(id, 2, 1, new byte[0], 1L));
    }

    @Test
    void timeoutIsAbsoluteEvenIfFragmentsKeepArriving() {
        UUID id = UUID.randomUUID();
        AdminTransfer transfer = new AdminTransfer(id, AdminTransfer.CHUNK_BYTES + 1, 1_000L);
        assertFalse(transfer.accept(id, AdminTransfer.CHUNK_BYTES + 1, 0,
                new byte[AdminTransfer.CHUNK_BYTES], 20_000L));
        assertThrows(IllegalArgumentException.class,
                () -> transfer.accept(id, AdminTransfer.CHUNK_BYTES + 1, 1, new byte[1], 31_000L));
    }

    @Test
    void rejectsMalformedUtf8() {
        UUID id = UUID.randomUUID();
        AdminTransfer transfer = new AdminTransfer(id, 2, 0L);
        transfer.accept(id, 2, 0, new byte[] {(byte) 0xc3, (byte) 0x28}, 1L);
        assertThrows(IllegalArgumentException.class, transfer::text);
        assertThrows(IllegalArgumentException.class, () -> AdminTransfer.encode("\ud800"));
    }
}
