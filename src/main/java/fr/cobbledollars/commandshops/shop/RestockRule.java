package fr.cobbledollars.commandshops.shop;

import java.time.ZoneId;

public sealed interface RestockRule permits RestockRule.IntervalRestockRule, RestockRule.DailyRestockRule {
    String type();

    record IntervalRestockRule(int amount, long everySeconds) implements RestockRule {
        public long everyMillis() {
            return Math.multiplyExact(everySeconds, 1000L);
        }

        @Override
        public String type() {
            return "interval";
        }
    }

    record DailyRestockRule(int hour, int minute, String timeZone) implements RestockRule {
        public ZoneId zoneId() {
            return ZoneId.of(timeZone);
        }

        @Override
        public String type() {
            return "daily_reset";
        }
    }
}
