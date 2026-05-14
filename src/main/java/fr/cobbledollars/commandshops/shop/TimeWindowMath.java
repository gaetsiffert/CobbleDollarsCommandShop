package fr.cobbledollars.commandshops.shop;

import java.util.List;

final class TimeWindowMath {
    private static final long DAY_TICKS = 24_000L;

    private TimeWindowMath() {
    }

    static long nextBoundaryDelayTicks(long timeOfDay, List<TimeWindow> windows) {
        if (windows.isEmpty()) {
            return Long.MAX_VALUE;
        }

        long normalizedTime = Math.floorMod(timeOfDay, DAY_TICKS);
        long nextDelay = Long.MAX_VALUE;
        for (TimeWindow window : windows) {
            nextDelay = Math.min(nextDelay, delayToBoundary(normalizedTime, window.startTick()));
            nextDelay = Math.min(nextDelay, delayToBoundary(normalizedTime, window.endTick()));
        }
        return nextDelay;
    }

    static boolean contains(long timeOfDay, int startTick, int endTick) {
        long normalizedTime = Math.floorMod(timeOfDay, DAY_TICKS);
        if (startTick < endTick) {
            return normalizedTime >= startTick && normalizedTime < endTick;
        }
        return normalizedTime >= startTick || normalizedTime < endTick;
    }

    private static long delayToBoundary(long normalizedTimeOfDay, int boundaryTick) {
        long delay = boundaryTick - normalizedTimeOfDay;
        if (delay <= 0L) {
            delay += DAY_TICKS;
        }
        return delay;
    }

    record TimeWindow(int startTick, int endTick) {
    }
}
