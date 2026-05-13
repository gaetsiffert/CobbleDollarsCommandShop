package fr.cobbledollars.commandshops;

public final class StackCountMath {
    private StackCountMath() {
    }

    public static long multiplyToLong(int left, int right) {
        return Math.multiplyFull(left, right);
    }

    public static boolean fitsInInt(long value) {
        return value >= 0L && value <= Integer.MAX_VALUE;
    }

    public static int toIntExact(long value, String context) {
        if (!fitsInInt(value)) {
            throw new IllegalArgumentException(context + " exceeds supported stack count range: " + value);
        }
        return (int) value;
    }
}
