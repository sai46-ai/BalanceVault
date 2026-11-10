package com.tapestry.balancevault.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/** SHA-256 helpers for the ledger and audit hash chains, plus constant-time comparison and random tokens. */
public final class Hashing {
    /** prev_hash of the first row in a chain. */
    public static final String GENESIS = "0".repeat(64);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashing() {
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of text. */
    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(digest(text));
    }

    /**
     * Hash of one chain link: SHA-256 over the JSON array [prev, ...fields]. A JSON array (not string joining)
     * keeps the encoding unambiguous, so "a|b","c" can never collide with "a","b|c". Fields may contain nulls.
     */
    public static String chainHash(String prevHash, List<?> fields) {
        List<Object> all = new ArrayList<>(fields.size() + 1);
        all.add(prevHash);
        all.addAll(fields);
        return sha256Hex(JSON.writeValueAsString(all));
    }

    /** Serialises a value to JSON text (used for audit details, which are hashed exactly as stored). */
    public static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    /** Compares two secrets without leaking their length or common prefix through timing. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(digest(a), digest(b));
    }

    /** 256-bit URL-safe random token (43 chars) for onboarding invitations. */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A random decimal digit string of the given length (account numbers). */
    public static String randomDigits(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(RANDOM.nextInt(10));
        }
        return out.toString();
    }

    /** Raw SHA-256 digest; SHA-256 is guaranteed on every Java platform, so the checked exception cannot occur. */
    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
