package com.tapestry.balancevault.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.tapestry.balancevault.support.ApiException;

/** Unit tests for Inputs validation patterns and sanitizers. */
class InputsValidationTest {

    private static final Pattern USERNAME_PATTERN = Pattern.compile(Inputs.USERNAME);
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(Inputs.PASSWORD);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile(Inputs.ACCOUNT_NUMBER);
    private static final Pattern REQUEST_KEY_PATTERN = Pattern.compile(Inputs.REQUEST_KEY);
    private static final Pattern TOKEN_PATTERN = Pattern.compile(Inputs.TOKEN);
    private static final Pattern ROLE_PATTERN = Pattern.compile(Inputs.ROLE);

    @Test
    void validatesUsernameFormat() {
        assertTrue(USERNAME_PATTERN.matcher("alice").matches());
        assertTrue(USERNAME_PATTERN.matcher("bob_123").matches());
        assertTrue(USERNAME_PATTERN.matcher("user.name-1").matches());

        // Invalid: too short, uppercase, disallowed characters
        assertFalse(USERNAME_PATTERN.matcher("al").matches());
        assertFalse(USERNAME_PATTERN.matcher("Alice").matches());
        assertFalse(USERNAME_PATTERN.matcher("user@domain").matches());
        assertFalse(USERNAME_PATTERN.matcher("a".repeat(33)).matches());
    }

    @Test
    void validatesPasswordComplexity() {
        // Must contain lowercase, uppercase, digit, and special character; 12+ chars
        assertTrue(PASSWORD_PATTERN.matcher("StrongPass@123").matches());
        assertTrue(PASSWORD_PATTERN.matcher("Correct-Horse-Battery-9!").matches());

        // Invalid passwords
        assertFalse(PASSWORD_PATTERN.matcher("short1!Aa").matches(), "Must be at least 12 characters");
        assertFalse(PASSWORD_PATTERN.matcher("alllowercase123!").matches(), "Missing uppercase");
        assertFalse(PASSWORD_PATTERN.matcher("ALLUPPERCASE123!").matches(), "Missing lowercase");
        assertFalse(PASSWORD_PATTERN.matcher("NoDigitsInPassword!").matches(), "Missing digit");
        assertFalse(PASSWORD_PATTERN.matcher("NoSpecialChars123").matches(), "Missing symbol");
    }

    @Test
    void validatesAccountNumberFormat() {
        assertTrue(ACCOUNT_PATTERN.matcher("BV0123456789").matches());
        assertTrue(ACCOUNT_PATTERN.matcher("BV9999999999").matches());

        assertFalse(ACCOUNT_PATTERN.matcher("bv0123456789").matches(), "Must be uppercase BV");
        assertFalse(ACCOUNT_PATTERN.matcher("BV123").matches(), "Must have 10 digits");
        assertFalse(ACCOUNT_PATTERN.matcher("AC0123456789").matches(), "Must start with BV");
        assertFalse(ACCOUNT_PATTERN.matcher("BV01234567890").matches(), "Must not exceed 10 digits");
    }

    @Test
    void validatesRequestKeyFormat() {
        assertTrue(REQUEST_KEY_PATTERN.matcher("c56a4180-65aa-42ec-a945-5fd21dec0538").matches());

        // Invalid UUIDs
        assertFalse(REQUEST_KEY_PATTERN.matcher("C56A4180-65AA-42EC-A945-5FD21DEC0538").matches(), "Must be lowercase");
        assertFalse(REQUEST_KEY_PATTERN.matcher("not-a-uuid").matches());
        assertFalse(REQUEST_KEY_PATTERN.matcher("c56a418065aa42eca9455fd21dec0538").matches(), "Missing hyphens");
    }

    @Test
    void validatesTokenFormat() {
        assertTrue(TOKEN_PATTERN.matcher("A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v").matches());

        assertFalse(TOKEN_PATTERN.matcher("short-token").matches());
        assertFalse(TOKEN_PATTERN.matcher("A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v_extra").matches());
    }

    @Test
    void validatesRoles() {
        assertTrue(ROLE_PATTERN.matcher("admin").matches());
        assertTrue(ROLE_PATTERN.matcher("operator").matches());
        assertTrue(ROLE_PATTERN.matcher("auditor").matches());
        assertTrue(ROLE_PATTERN.matcher("customer").matches());

        assertFalse(ROLE_PATTERN.matcher("superuser").matches());
        assertFalse(ROLE_PATTERN.matcher("ADMIN").matches());
    }

    @Test
    void enforcesLimitBounds() {
        assertEquals(50, Inputs.limit(null));
        assertEquals(1, Inputs.limit(1));
        assertEquals(100, Inputs.limit(100));
        assertEquals(200, Inputs.limit(200));

        assertThrows(ApiException.class, () -> Inputs.limit(0));
        assertThrows(ApiException.class, () -> Inputs.limit(-5));
        assertThrows(ApiException.class, () -> Inputs.limit(201));
    }

    @Test
    void sanitizesOptionalText() {
        assertNull(Inputs.optionalText(null));
        assertNull(Inputs.optionalText(""));
        assertNull(Inputs.optionalText("   "));
        assertEquals("Hello World", Inputs.optionalText("  Hello World  "));
    }
}
