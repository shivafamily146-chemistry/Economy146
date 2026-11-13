package io.github.shivafamily146.indianeconomy.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE, 2);

    private Money() {
    }

    public static long toPaise(String input) {
        return parsePaise(input, false);
    }

    public static long toPaiseAllowZero(String input) {
        return parsePaise(input, true);
    }

    private static long parsePaise(String input, boolean allowZero) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Amount is required.");
        }
        try {
            BigDecimal amount = new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY);
            if ((allowZero ? amount.signum() < 0 : amount.signum() <= 0) || amount.compareTo(MAX) > 0) {
                throw new IllegalArgumentException("Amount must be " + (allowZero ? "non-negative" : "positive")
                        + " and within the supported range.");
            }
            return amount.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Amount must be a valid number with at most two decimals.", exception);
        }
    }

    public static long toPaise(BigDecimal amount) {
        return toPaise(amount.toPlainString());
    }

    public static String format(long paise) {
        return BigDecimal.valueOf(paise, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
