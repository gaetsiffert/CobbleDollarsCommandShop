package fr.cobbledollars.commandshops.admin.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static fr.cobbledollars.commandshops.admin.network.AdminOperationGuard.Delivery.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminOperationGuardTest {
    private final AdminOperationGuard guard = new AdminOperationGuard();

    @BeforeEach
    void openEditor() {
        guard.attach();
    }

    private UUID begin(String draft, boolean save) {
        UUID id = UUID.randomUUID();
        guard.begin(id, draft, save);
        return id;
    }

    @Test
    void appliesMatchingSaveResultAndSnapshotWhenDraftWasNotEdited() {
        UUID save = begin("submitted", true);

        assertEquals(APPLY, guard.result(save, true));
        assertEquals(APPLY, guard.snapshot(save, "submitted"));
    }

    @Test
    void preservesNewEditsWhileOfferingTheSavedServerSnapshot() {
        UUID save = begin("submitted", true);

        assertEquals(APPLY, guard.result(save, true));
        assertEquals(DEFER, guard.snapshot(save, "edited after submission"));
        assertEquals(IGNORE, guard.snapshot(save, "submitted"));
    }

    @Test
    void delayedSuccessfulSaveAfterTimeoutRemainsAvailableWithoutReplacingDraft() {
        UUID save = begin("submitted", true);

        assertTrue(guard.expire(save));
        assertEquals(DEFER, guard.result(save, true));
        assertTrue(guard.isSave(save));
        assertEquals(DEFER, guard.snapshot(save, "submitted"));
    }

    @Test
    void expiredReloadDoesNotApplyEvenIfTheDraftLooksUnchanged() {
        UUID reload = begin("draft", false);

        assertTrue(guard.expire(reload));
        assertEquals(DEFER, guard.snapshot(reload, "draft"));
        assertFalse(guard.isSave(reload));
    }

    @Test
    void previousResultCannotCompleteTheNewerRequest() {
        UUID oldSave = begin("old", true);
        UUID reload = begin("current", false);

        assertEquals(DEFER, guard.result(oldSave, true));
        assertEquals(DEFER, guard.snapshot(oldSave, "current"));
        assertEquals(APPLY, guard.snapshot(reload, "current"));
    }

    @Test
    void ignoresOlderSnapshotAndResultAfterNewerServerStateWasReceived() {
        UUID oldSave = begin("old", true);
        UUID reload = begin("current", false);

        assertEquals(APPLY, guard.snapshot(reload, "current"));
        assertEquals(IGNORE, guard.snapshot(oldSave, "current"));
        assertEquals(IGNORE, guard.result(oldSave, true));
    }

    @Test
    void closingTheScreenRejectsPendingReplies() {
        UUID save = begin("draft", true);
        guard.detach();

        assertEquals(IGNORE, guard.result(save, true));
        assertEquals(IGNORE, guard.snapshot(save, "draft"));
        assertFalse(guard.expire(save));
    }

    @Test
    void aNewScreenCannotReceiveAnEarlierScreensReplies() {
        UUID save = begin("same text", true);
        guard.detach();
        guard.attach();
        UUID reload = begin("same text", false);

        assertEquals(IGNORE, guard.result(save, true));
        assertEquals(IGNORE, guard.snapshot(save, "same text"));
        assertEquals(APPLY, guard.snapshot(reload, "same text"));
    }

    @Test
    void disconnectDiscardsOperationHistoryBeforeAnotherServerIsOpened() {
        UUID save = begin("draft", true);
        guard.clear();
        guard.attach();

        assertEquals(IGNORE, guard.result(save, true));
        assertEquals(IGNORE, guard.snapshot(save, "draft"));
    }

    @Test
    void aLateResultAfterTheSnapshotCannotRestartTheWaitingState() {
        UUID save = begin("draft", true);

        assertEquals(APPLY, guard.snapshot(save, "draft"));
        assertEquals(IGNORE, guard.result(save, true));
    }

    @Test
    void rejectedSaveCannotApplyAnUnexpectedSnapshot() {
        UUID save = begin("draft", true);

        assertEquals(APPLY, guard.result(save, false));
        assertEquals(IGNORE, guard.snapshot(save, "draft"));
    }

    @Test
    void duplicateOrUnknownResultsCannotAffectTheEditor() {
        UUID save = begin("draft", true);

        assertEquals(IGNORE, guard.result(UUID.randomUUID(), false));
        assertEquals(IGNORE, guard.snapshot(UUID.randomUUID(), "draft"));
        assertEquals(APPLY, guard.result(save, true));
        assertEquals(IGNORE, guard.result(save, true));
        assertEquals(APPLY, guard.snapshot(save, "draft"));
    }

    @Test
    void expiringAnOlderOperationDoesNotExpireTheNewerRequest() {
        UUID save = begin("old", true);
        UUID reload = begin("new", false);

        assertFalse(guard.expire(save));
        assertEquals(APPLY, guard.snapshot(reload, "new"));
    }

    @Test
    void operationIdsCannotBeReusedForAnotherDraft() {
        UUID save = begin("first", true);

        assertThrows(IllegalArgumentException.class, () -> guard.begin(save, "second", true));
    }

    @Test
    void repeatedInitializationOfTheSameScreenDoesNotLoseThePendingRequest() {
        UUID reload = begin("draft", false);
        guard.attach();

        assertEquals(APPLY, guard.snapshot(reload, "draft"));
    }
}
