package com.tapestry.balancevault.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Walks a hash chain row by row (ids 1, 2, 3 ...) and collects every break: a gap in ids, a prev_hash that does not
 * point at the previous row, or a stored hash that no longer matches the recomputed one (the row was edited).
 */
public final class ChainVerifier {
    private static final int MAX_REPORTED = 100;

    private long expectedId = 1;
    private String head = Hashing.GENESIS;
    private long rows;
    private long problemCount;
    private final List<String> problems = new ArrayList<>();

    /** Checks the next row in id order. recomputedHash is the hash calculated from the row's stored content. */
    public void next(long id, String storedPrevHash, String storedHash, String recomputedHash) {
        if (id != expectedId) {
            problem("row " + id + ": expected id " + expectedId + " (rows missing or inserted)");
        }
        if (!Objects.equals(storedPrevHash, head)) {
            problem("row " + id + ": prev_hash does not link to the previous row");
        }
        if (!Objects.equals(storedHash, recomputedHash)) {
            problem("row " + id + ": content does not match its hash (row altered)");
        }
        expectedId = id + 1;
        head = storedHash;
        rows++;
    }

    /** Records a problem found outside the hash itself (e.g. an unbalanced journal). */
    public void problem(String message) {
        problemCount++;
        if (problems.size() < MAX_REPORTED) {
            problems.add(message);
        }
    }

    /** True when no problem was recorded. */
    public boolean ok() {
        return problemCount == 0;
    }

    /** Summary for the API: ok, rows, headHash, problemCount, problems (first 100), plus any extra fields. */
    public Map<String, Object> result(Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok());
        out.put("rows", rows);
        out.put("headHash", head);
        out.putAll(extra);
        out.put("problemCount", problemCount);
        out.put("problems", problems);
        return out;
    }
}
