package fr.cobbledollars.commandshops.audit;

import java.math.BigInteger;
import java.util.Objects;

/** Actual balances, rather than an operation's requested or unmultiplied amount. */
public record AuditBalanceChange(AuditPlayerRef player, BigInteger before, BigInteger after) {
    public AuditBalanceChange {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }

    public BigInteger delta() {
        return after.subtract(before);
    }
}
