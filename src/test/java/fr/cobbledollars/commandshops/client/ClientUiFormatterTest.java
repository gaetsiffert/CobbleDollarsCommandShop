package fr.cobbledollars.commandshops.client;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientUiFormatterTest {
    @Test
    void unitPriceUsesEachItemRatherThanTheWholeBundle() {
        assertEquals("50", unitPrice("250", 5));
        assertEquals("12.5", unitPrice("100", 8));
        assertEquals("0.125", unitPrice("1", 8));
        assertEquals("≈ 0.016", unitPrice("1", 64));
        assertEquals("≈ 0.001", unitPrice("1", 1024));
        assertEquals("0", unitPrice("0", 5));
        assertEquals("250", unitPrice("250", 1));
    }

    @Test
    void repeatingFractionsAreRoundedAndExplicitlyMarkedAsApproximate() {
        assertEquals("≈ 33.333", unitPrice("100", 3));
        assertEquals("≈ 0.667", unitPrice("2", 3));
        assertEquals("≈ 0", unitPrice("1", Integer.MAX_VALUE));
    }

    @Test
    void largePricesKeepTheirFullPrecisionWithoutFloatingPointConversion() {
        assertEquals("4503599627370496.5", unitPrice("9007199254740993", 2));
        assertEquals("16666666666666666666666666666666666666.5",
                unitPrice("99999999999999999999999999999999999999", 6));
    }

    private static String unitPrice(String price, int count) {
        return ClientUiFormatter.formatUnitPriceText(new BigInteger(price), count);
    }
}
