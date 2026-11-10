package com.tapestry.balancevault.web;

import com.tapestry.balancevault.support.ApiException;

/** Allow-list patterns shared by request records, plus small normalisers. */
public final class Inputs {
    public static final String USERNAME = "^[a-z0-9._-]{3,32}$";
    public static final String DISPLAY_NAME = "^[\\p{L}\\p{M}][\\p{L}\\p{M}' .-]{0,79}$";
    public static final String PASSWORD = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,128}$";
    /** Free text (memos, reasons): any characters except control/format/private-use code points. */
    public static final String FREE_TEXT = "^[^\\p{C}]*$";
    public static final String REQUEST_KEY = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    public static final String ACCOUNT_NUMBER = "^BV\\d{10}$";
    public static final String TOKEN = "^[A-Za-z0-9_-]{43}$";
    public static final String ROLE = "^(admin|operator|auditor|customer)$";

    private Inputs() {
    }

    /** Page size for list endpoints: 50 when omitted, 1-200 otherwise. */
    public static int limit(Integer limit) {
        int n = limit == null ? 50 : limit;
        if (n < 1 || n > 200) {
            throw new ApiException(400, "Invalid input: limit must be between 1 and 200");
        }
        return n;
    }

    /** Trims optional free text; blank becomes null. */
    public static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
