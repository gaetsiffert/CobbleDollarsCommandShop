package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static fr.cobbledollars.commandshops.admin.network.AdminLiveOperations.Acceptance.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminLiveOperationsTest {
    @Test
    void rejectsReplayedMutationIdsAcrossRateLimitWindows() {
        AdminLiveOperations operations = new AdminLiveOperations();
        UUID mutation = UUID.randomUUID();
        assertEquals(ACCEPT, operations.accept(mutation, 1000));
        assertEquals(DUPLICATE, operations.accept(mutation, 2000));
        assertEquals(DUPLICATE, operations.accept(mutation, 500000));
    }

    @Test
    void boundsBurstsAndAllowsAFreshRequestAfterTheWindow() {
        AdminLiveOperations operations = new AdminLiveOperations();
        for (int index = 0; index < 8; index++) assertEquals(ACCEPT, operations.accept(UUID.randomUUID(), 1000));
        assertEquals(THROTTLED, operations.accept(UUID.randomUUID(), 1999));
        assertEquals(ACCEPT, operations.accept(UUID.randomUUID(), 2000));
    }

    @Test
    void boundsTheReplayHistoryWithoutEvictingOldMutationIds() {
        AdminLiveOperations operations = new AdminLiveOperations();
        UUID first = UUID.randomUUID();
        assertEquals(ACCEPT, operations.accept(first, 1000));
        for (int index = 1; index < AdminLiveOperations.MAX_OPERATIONS; index++)
            assertEquals(ACCEPT, operations.accept(UUID.randomUUID(), 1000L + index * 1000L));
        assertEquals(EXHAUSTED, operations.accept(UUID.randomUUID(), 6000000));
        assertEquals(DUPLICATE, operations.accept(first, 6000000));
    }
}
