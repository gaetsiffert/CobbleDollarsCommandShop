package fr.cobbledollars.commandshops.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public enum AuditTimeWindow {
    ALL("all", null, "cobbledollarscommandshops.command.stats.window.all"),
    LAST_24_HOURS("24h", Duration.ofHours(24), "cobbledollarscommandshops.command.stats.window.24h"),
    LAST_7_DAYS("7d", Duration.ofDays(7), "cobbledollarscommandshops.command.stats.window.7d"),
    LAST_30_DAYS("30d", Duration.ofDays(30), "cobbledollarscommandshops.command.stats.window.30d");

    private final String argumentValue;
    private final Duration duration;
    private final String translationKey;

    AuditTimeWindow(String argumentValue, Duration duration, String translationKey) {
        this.argumentValue = argumentValue;
        this.duration = duration;
        this.translationKey = translationKey;
    }

    public String argumentValue() {
        return argumentValue;
    }

    public String translationKey() {
        return translationKey;
    }

    public boolean includes(Instant timestamp, Instant now) {
        if (duration == null) {
            return true;
        }
        return !timestamp.isBefore(now.minus(duration));
    }

    public static AuditTimeWindow fromArgument(String argumentValue) {
        for (AuditTimeWindow window : values()) {
            if (window.argumentValue.equalsIgnoreCase(argumentValue)) {
                return window;
            }
        }
        return null;
    }

    public static List<String> suggestionValues() {
        return List.of("all", "24h", "7d", "30d");
    }
}
