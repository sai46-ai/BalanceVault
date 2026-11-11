package com.tapestry.balancevault.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for cryptographic helpers, constant-time comparison, and token generation. */
class HashingSecurityTest {

    @Test
    void genesisConstantIs64Zeros() {
        assertEquals(64, Hashing.GENESIS.length());
        assertTrue(Hashing.GENESIS.chars().allMatch(c -> c == '0'));
    }

    @Test
    void constantTimeComparisonHandlesEquality() {
        assertTrue(Hashing.constantTimeEquals("secret123", "secret123"));
        assertFalse(Hashing.constantTimeEquals("secret123", "secret124"));
        assertFalse(Hashing.constantTimeEquals("secret123", "secret"));
        assertFalse(Hashing.constantTimeEquals("secret", "secret123"));
        assertFalse(Hashing.constantTimeEquals("", "secret"));
        assertTrue(Hashing.constantTimeEquals("", ""));
    }

    @Test
    void randomTokenGeneratesValidUrlSafeTokens() {
        String token1 = Hashing.randomToken();
        String token2 = Hashing.randomToken();

        assertNotNull(token1);
        assertEquals(43, token1.length());
        assertEquals(43, token2.length());
        assertNotEquals(token1, token2, "Subsequent tokens must be statistically distinct");

        // Verify base64url character set (no +, /, or = padding)
        assertTrue(token1.matches("^[A-Za-z0-9_-]{43}$"));
    }

    @Test
    void randomDigitsGeneratesFixedLengthNumericStrings() {
        String digits10 = Hashing.randomDigits(10);
        String digits16 = Hashing.randomDigits(16);

        assertEquals(10, digits10.length());
        assertEquals(16, digits16.length());
        assertTrue(digits10.chars().allMatch(Character::isDigit));
        assertTrue(digits16.chars().allMatch(Character::isDigit));
    }

    @Test
    void sha256HexProducesValidHexDigests() {
        String hash = Hashing.sha256Hex("hello world");
        assertEquals(64, hash.length());
        // Standard SHA-256 for "hello world"
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }

    @Test
    void chainHashPreservesFieldBoundaries() {
        String prev = Hashing.GENESIS;
        String h1 = Hashing.chainHash(prev, List.of("account1", 100));
        String h2 = Hashing.chainHash(prev, List.of("account1", 100));
        String h3 = Hashing.chainHash(prev, List.of("account10", 0));

        assertEquals(h1, h2, "Identical inputs must yield identical chain hashes");
        assertNotEquals(h1, h3, "Different fields must not collide");
    }
}
