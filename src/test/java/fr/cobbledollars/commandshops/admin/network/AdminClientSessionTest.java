package fr.cobbledollars.commandshops.admin.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AdminClientSessionTest {
    private static final UUID OPEN = new UUID(0L, 0L);
    private final AdminClientSession session = new AdminClientSession();

    @Test
    void reloadAfterServerExpiryAdoptsTheReplacementNonceForTheNextSave() {
        UUID expired = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        UUID reload = UUID.randomUUID();
        session.acceptSnapshot(expired, OPEN);
        session.requested(reload);

        assertTrue(session.acceptSnapshot(replacement, reload));
        assertEquals(replacement, session.id());
    }

    @Test
    void lateOldResponseCannotRestoreAnExpiredNonce() {
        UUID oldSession = UUID.randomUUID();
        UUID newSession = UUID.randomUUID();
        UUID oldSave = UUID.randomUUID();
        UUID reload = UUID.randomUUID();
        session.acceptSnapshot(oldSession, OPEN);
        session.requested(oldSave);
        session.requested(reload);
        session.acceptSnapshot(newSession, reload);

        assertFalse(session.acceptSnapshot(oldSession, oldSave));
        assertEquals(newSession, session.id());
    }

    @Test
    void serverCommandOpenSupersedesEveryEarlierEditorRequest() {
        UUID oldSave = UUID.randomUUID();
        UUID latestSession = UUID.randomUUID();
        session.requested(oldSave);
        session.acceptSnapshot(latestSession, OPEN);

        assertFalse(session.acceptSnapshot(UUID.randomUUID(), oldSave));
        assertEquals(latestSession, session.id());
    }

    @Test
    void unknownAndDisconnectedRepliesCannotEstablishASession() {
        assertFalse(session.acceptSnapshot(UUID.randomUUID(), UUID.randomUUID()));
        assertNull(session.id());
        UUID reload = UUID.randomUUID();
        session.requested(reload);
        session.clear();

        assertFalse(session.acceptSnapshot(UUID.randomUUID(), reload));
        assertNull(session.id());
    }
}
