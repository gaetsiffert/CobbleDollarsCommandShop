package fr.cobbledollars.commandshops.shop;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWindowMathTest {
    @Test
    void nextBoundaryDelayIsUnboundedWithoutTimeWindows() {
        assertEquals(Long.MAX_VALUE, TimeWindowMath.nextBoundaryDelayTicks(6_000L, List.of()));
    }

    @Test
    void nextBoundaryDelaySupportsWrapAroundWindows() {
        List<TimeWindowMath.TimeWindow> windows = List.of(new TimeWindowMath.TimeWindow(13_000, 2_000));

        assertEquals(1L, TimeWindowMath.nextBoundaryDelayTicks(12_999L, windows));
        assertEquals(13_000L, TimeWindowMath.nextBoundaryDelayTicks(13_000L, windows));
        assertEquals(1L, TimeWindowMath.nextBoundaryDelayTicks(1_999L, windows));
        assertEquals(11_000L, TimeWindowMath.nextBoundaryDelayTicks(2_000L, windows));
    }

    @Test
    void nextBoundaryDelayChoosesClosestBoundaryAcrossWindows() {
        List<TimeWindowMath.TimeWindow> windows = List.of(
                new TimeWindowMath.TimeWindow(1_000, 4_000),
                new TimeWindowMath.TimeWindow(12_000, 14_000)
        );

        assertEquals(500L, TimeWindowMath.nextBoundaryDelayTicks(500L, windows));
        assertEquals(2_000L, TimeWindowMath.nextBoundaryDelayTicks(2_000L, windows));
        assertEquals(1_000L, TimeWindowMath.nextBoundaryDelayTicks(11_000L, windows));
    }

    @Test
    void containsHandlesNormalAndWrapAroundWindows() {
        assertTrue(TimeWindowMath.contains(1_000L, 1_000, 6_000));
        assertTrue(TimeWindowMath.contains(5_999L, 1_000, 6_000));
        assertFalse(TimeWindowMath.contains(6_000L, 1_000, 6_000));

        assertTrue(TimeWindowMath.contains(13_000L, 13_000, 2_000));
        assertTrue(TimeWindowMath.contains(23_999L, 13_000, 2_000));
        assertTrue(TimeWindowMath.contains(1_999L, 13_000, 2_000));
        assertFalse(TimeWindowMath.contains(2_000L, 13_000, 2_000));
    }

    @Test
    void containsNormalizesTimeOfDay() {
        assertTrue(TimeWindowMath.contains(25_000L, 1_000, 2_000));
        assertFalse(TimeWindowMath.contains(-1L, 1_000, 2_000));
    }
}
