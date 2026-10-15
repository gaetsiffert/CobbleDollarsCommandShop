package fr.cobbledollars.commandshops.admin.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Client operation lifetime rules, independent of the UI and Minecraft network runtime. */
public final class AdminOperationGuard {
    public enum Delivery { APPLY, DEFER, IGNORE }

    private static final int MAX_HISTORY = 8;
    private final Map<UUID, Operation> operations = new LinkedHashMap<>();
    private long binding;
    private boolean attached;
    private UUID latest;
    private long sequence;
    private long newestSnapshot;

    public void attach() {
        if (!attached) {
            attached = true;
            binding++;
        }
    }

    public void detach() {
        attached = false;
        latest = null;
        operations.clear();
    }

    public void clear() {
        detach();
        binding++;
    }

    public void begin(UUID id, String draft, boolean save) {
        if (!attached) throw new IllegalStateException("The editor is closed.");
        if (operations.containsKey(id)) throw new IllegalArgumentException("Operation id already used.");
        operations.put(id, new Operation(binding, ++sequence, Objects.requireNonNull(draft), save));
        latest = id;
        while (operations.size() > MAX_HISTORY) operations.remove(operations.keySet().iterator().next());
    }

    /** Expiration retains the operation: a successful save may still complete on the server. */
    public boolean expire(UUID id) {
        Operation operation = matching(id);
        if (operation == null || operation.finished) return false;
        operation.expired = true;
        return id.equals(latest);
    }

    public Delivery result(UUID id, boolean success) {
        Operation operation = matching(id);
        if (operation == null || operation.finished || operation.resultSeen || operation.sequence < newestSnapshot)
            return Delivery.IGNORE;
        operation.resultSeen = true;
        if (!success) operation.finished = true;
        return operation.expired || !id.equals(latest) ? Delivery.DEFER : Delivery.APPLY;
    }

    public Delivery snapshot(UUID id, String currentDraft) {
        Operation operation = matching(id);
        if (operation == null || operation.finished || operation.sequence < newestSnapshot) return Delivery.IGNORE;
        operation.finished = true;
        newestSnapshot = operation.sequence;
        return !operation.expired && id.equals(latest) && operation.draft.equals(currentDraft)
                ? Delivery.APPLY : Delivery.DEFER;
    }

    public boolean isSave(UUID id) {
        Operation operation = matching(id);
        return operation != null && operation.save;
    }

    private Operation matching(UUID id) {
        Operation operation = operations.get(id);
        return attached && operation != null && operation.binding == binding ? operation : null;
    }

    private static final class Operation {
        final long binding;
        final long sequence;
        final String draft;
        final boolean save;
        boolean expired;
        boolean finished;
        boolean resultSeen;

        Operation(long binding, long sequence, String draft, boolean save) {
            this.binding = binding;
            this.sequence = sequence;
            this.draft = draft;
            this.save = save;
        }
    }
}
