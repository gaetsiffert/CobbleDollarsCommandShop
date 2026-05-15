package fr.cobbledollars.commandshops.audit;

import java.util.EnumSet;

public record AuditConfig(boolean enabled, boolean flushEachEntry, EnumSet<AuditEventType> enabledEvents) {
    public AuditConfig {
        enabledEvents = enabledEvents.isEmpty() ? EnumSet.noneOf(AuditEventType.class) : EnumSet.copyOf(enabledEvents);
    }

    public static AuditConfig defaults() {
        EnumSet<AuditEventType> enabledEvents = EnumSet.noneOf(AuditEventType.class);
        for (AuditEventType eventType : AuditEventType.values()) {
            if (eventType.enabledByDefault()) {
                enabledEvents.add(eventType);
            }
        }
        return new AuditConfig(true, true, enabledEvents);
    }

    public boolean isEnabled(AuditEventType eventType) {
        return enabled && enabledEvents.contains(eventType);
    }
}
