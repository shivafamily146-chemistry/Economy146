package io.github.shivafamily146.indianeconomy.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {
    @Test
    void parsesDecimalAmountsWithoutFloatingPointLoss() {
        assertEquals(123456L, Money.toPaise("1234.56"));
        assertEquals("0.10", Money.format(Money.toPaise("0.10")));
    }

    @Test
    void rejectsZeroNegativeExcessPrecisionAndMalformedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.toPaise("0"));
        assertThrows(IllegalArgumentException.class, () -> Money.toPaise("-1.00"));
        assertThrows(IllegalArgumentException.class, () -> Money.toPaise("1.001"));
        assertThrows(IllegalArgumentException.class, () -> Money.toPaise("NaN"));
    }

    @Test
    void acceptsMaximumRepresentablePaisaAndRejectsOverflow() {
        assertEquals(Long.MAX_VALUE, Money.toPaise("92233720368547758.07"));
        assertThrows(IllegalArgumentException.class, () -> Money.toPaise("92233720368547758.08"));
    }
}
