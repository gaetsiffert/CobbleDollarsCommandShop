package fr.cobbledollars.commandshops.shop;

public sealed interface RestockRule permits RestockRule.IntervalRestockRule, RestockRule.DailyRestockRule {
    String type();

    record IntervalRestockRule(int amount, long everySeconds) implements RestockRule {
        @Override
        public String type() {
            return "interval";
        }
    }

    record DailyRestockRule(int hour, int minute, String timeZone) implements RestockRule {
        @Override
        public String type() {
            return "daily_reset";
        }
    }
}
