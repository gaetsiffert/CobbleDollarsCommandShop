package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;

/** The current session nonce may advance only with the latest requested state. */
final class AdminClientSession {
    private static final UUID INITIAL_OPEN = new UUID(0L, 0L);
    private UUID id;
    private UUID latestOperation;

    UUID id() {
        return id;
    }

    void requested(UUID operationId) {
        latestOperation = operationId;
    }

    boolean acceptSnapshot(UUID sessionId, UUID operationId) {
        if (INITIAL_OPEN.equals(operationId)) {
            id = sessionId;
            latestOperation = null;
            return true;
        }
        if (operationId.equals(latestOperation)) {
            id = sessionId;
            return true;
        }
        return false;
    }

    void clear() {
        id = null;
        latestOperation = null;
    }
}
