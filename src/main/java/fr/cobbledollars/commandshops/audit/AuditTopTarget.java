package fr.cobbledollars.commandshops.audit;

import java.util.List;

public enum AuditTopTarget {
    SHOPS("shops", "cobbledollarscommandshops.command.stats.top.target.shops"),
    OFFERS("offers", "cobbledollarscommandshops.command.stats.top.target.offers"),
    PLAYERS("players", "cobbledollarscommandshops.command.stats.top.target.players"),
    ITEMS("items", "cobbledollarscommandshops.command.stats.top.target.items");

    private final String argumentValue;
    private final String translationKey;

    AuditTopTarget(String argumentValue, String translationKey) {
        this.argumentValue = argumentValue;
        this.translationKey = translationKey;
    }

    public String argumentValue() {
        return argumentValue;
    }

    public String translationKey() {
        return translationKey;
    }

    public static AuditTopTarget fromArgument(String argumentValue) {
        for (AuditTopTarget target : values()) {
            if (target.argumentValue.equalsIgnoreCase(argumentValue)) {
                return target;
            }
        }
        return null;
    }

    public static List<String> suggestionValues() {
        return List.of("shops", "offers", "players", "items");
    }
}
