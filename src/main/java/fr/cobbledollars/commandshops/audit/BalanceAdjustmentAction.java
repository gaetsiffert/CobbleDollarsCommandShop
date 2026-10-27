package fr.cobbledollars.commandshops.audit;

import java.util.Locale;

public enum BalanceAdjustmentAction {
    GIVE, REMOVE, SET;

    public String eventName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
