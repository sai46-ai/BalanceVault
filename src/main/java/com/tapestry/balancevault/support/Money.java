package com.tapestry.balancevault.support;

import java.util.regex.Pattern;

/** Parses user-entered decimal amounts into integer cents without ever touching floating point. */
public final class Money {
    /** Largest single movement: 1,000,000.00. */
    public static final long MAX_CENTS = 100_000_000L;
    private static final Pattern AMOUNT = Pattern.compile("^\\d{1,9}(\\.\\d{1,2})?$");

    private Money() {
    }

    /** "125.5" -> 12550. Rejects signs, exponents, more than 2 decimals, zero, and anything above MAX_CENTS. */
    public static long toCents(String amount) {
        if (amount == null || !AMOUNT.matcher(amount).matches()) {
            throw new ApiException(400, "Invalid input: amount must be a number like 125.50");
        }
        String[] parts = amount.split("\\.", 2);
        long cents = Long.parseLong(parts[0]) * 100;
        if (parts.length == 2) {
            cents += Long.parseLong((parts[1] + "0").substring(0, 2));
        }
        if (cents < 1 || cents > MAX_CENTS) {
            throw new ApiException(400, "Invalid input: amount must be between 0.01 and 1,000,000.00");
        }
        return cents;
    }
}
