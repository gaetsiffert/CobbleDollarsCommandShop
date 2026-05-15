package fr.cobbledollars.commandshops.audit;

import java.util.Locale;

public enum AuditEventType {
    BUY_SUCCESS(true),
    BUY_FAILURE(false),
    SELL_SUCCESS(true),
    SELL_FAILURE(false),
    VISIBILITY_CHANGED(true);

    private final boolean enabledByDefault;

    AuditEventType(boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String eventName() {
        return configKey();
    }

    public static AuditEventType fromEventName(String eventName) {
        for (AuditEventType value : values()) {
            if (value.eventName().equals(eventName)) {
                return value;
            }
        }
        return null;
    }
}
