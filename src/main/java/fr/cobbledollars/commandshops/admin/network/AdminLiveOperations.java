package fr.cobbledollars.commandshops.admin.network;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Session lifetime replay protection and bounded bursts, independent of Minecraft. */
final class AdminLiveOperations {
    static final int MAX_OPERATIONS = 4096;
    private static final int BURST = 8;
    private final Set<UUID> received = new HashSet<>();
    private long windowStarted;
    private int windowCount;

    enum Acceptance { ACCEPT, DUPLICATE, THROTTLED, EXHAUSTED }

    Acceptance accept(UUID operation, long now) {
        if (received.contains(operation)) return Acceptance.DUPLICATE;
        if (received.size() >= MAX_OPERATIONS) return Acceptance.EXHAUSTED;
        if (now - windowStarted >= 1_000L) {
            windowStarted = now;
            windowCount = 0;
        }
        if (windowCount >= BURST) return Acceptance.THROTTLED;
        windowCount++;
        received.add(operation);
        return Acceptance.ACCEPT;
    }
}
