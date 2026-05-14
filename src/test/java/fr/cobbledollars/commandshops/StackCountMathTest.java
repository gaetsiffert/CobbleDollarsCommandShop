package fr.cobbledollars.commandshops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackCountMathTest {
    @Test
    void multiplyToLongKeepsFullPrecision() {
        assertEquals(2_147_483_648L, StackCountMath.multiplyToLong(Integer.MAX_VALUE, 1) + 1L);
        assertEquals(4_611_686_014_132_420_609L, StackCountMath.multiplyToLong(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void fitsInIntAcceptsOnlyNonNegativeIntRange() {
        assertTrue(StackCountMath.fitsInInt(0L));
        assertTrue(StackCountMath.fitsInInt(Integer.MAX_VALUE));
        assertFalse(StackCountMath.fitsInInt(-1L));
        assertFalse(StackCountMath.fitsInInt((long) Integer.MAX_VALUE + 1L));
    }

    @Test
    void toIntExactReturnsValueWithinSupportedRange() {
        assertEquals(64, StackCountMath.toIntExact(64L, "bundle size"));
        assertEquals(Integer.MAX_VALUE, StackCountMath.toIntExact(Integer.MAX_VALUE, "max"));
    }

    @Test
    void toIntExactRejectsNegativeValues() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StackCountMath.toIntExact(-1L, "bundle size")
        );

        assertEquals("bundle size exceeds supported stack count range: -1", exception.getMessage());
    }

    @Test
    void toIntExactRejectsOverflow() {
        long overflowValue = (long) Integer.MAX_VALUE + 1L;
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> StackCountMath.toIntExact(overflowValue, "bundle size")
        );

        assertEquals("bundle size exceeds supported stack count range: " + overflowValue, exception.getMessage());
    }
}
