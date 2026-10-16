package fr.cobbledollars.commandshops.admin.network;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdminLiveClientGuardTest {
    @Test
    void ignoresAResponseForThePreviousShopOrPlayerAndDeliversTheNewestOnlyOnce() {
        AdminLiveClientGuard guard = new AdminLiveClientGuard();
        guard.attach();
        UUID previous = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        guard.begin(previous);
        guard.begin(current);
        assertFalse(guard.accept(previous));
        assertTrue(guard.accept(current));
        assertFalse(guard.accept(current));
    }

    @Test
    void cannotResurrectAClosedEditorOrFillANewEditorWithAnOldReply() {
        AdminLiveClientGuard guard = new AdminLiveClientGuard();
        guard.attach();
        UUID operation = UUID.randomUUID();
        guard.begin(operation);
        guard.detach();
        assertFalse(guard.accept(operation));
        assertThrows(IllegalStateException.class, () -> guard.begin(UUID.randomUUID()));
        guard.attach();
        assertFalse(guard.accept(operation));
    }

    @Test
    void discardsInFlightStateWhenTheViewChangesWithoutAnotherRequest() {
        AdminLiveClientGuard guard = new AdminLiveClientGuard();
        guard.attach();
        UUID operation = UUID.randomUUID();
        guard.begin(operation);
        guard.invalidate();
        assertFalse(guard.accept(operation));
    }
}
