package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;

/** Runtime replies are useful only for the newest selection of the currently attached screen. */
public final class AdminLiveClientGuard {
    private boolean attached;
    private UUID pending;

    public void attach() {
        attached = true;
        pending = null;
    }

    public void detach() {
        attached = false;
        pending = null;
    }

    public void begin(UUID operationId) {
        if (!attached) throw new IllegalStateException("The editor is closed.");
        pending = operationId;
    }

    public void invalidate() {
        pending = null;
    }

    public boolean accept(UUID operationId) {
        if (!attached || pending == null || !pending.equals(operationId)) return false;
        pending = null;
        return true;
    }
}
