package fr.cobbledollars.commandshops.shop;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestockRuleTest {
    @Test
    void intervalRuleConvertsSecondsToMillis() {
        RestockRule.IntervalRestockRule rule = new RestockRule.IntervalRestockRule(3, 600L);

        assertEquals(600_000L, rule.everyMillis());
    }

    @Test
    void intervalRuleFailsFastOnMillisOverflow() {
        RestockRule.IntervalRestockRule rule = new RestockRule.IntervalRestockRule(1, Long.MAX_VALUE);

        assertThrows(ArithmeticException.class, rule::everyMillis);
    }

    @Test
    void dailyRuleReturnsItsConfiguredZoneId() {
        RestockRule.DailyRestockRule rule = new RestockRule.DailyRestockRule(4, 30, "Europe/Paris");

        assertEquals(ZoneId.of("Europe/Paris"), rule.zoneId());
    }
}
