package com.tapestry.balancevault.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tapestry.balancevault.ledger.LedgerService.Leg;
import com.tapestry.balancevault.security.Roles;
import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.ChainVerifier;
import com.tapestry.balancevault.support.Hashing;
import com.tapestry.balancevault.support.Money;

/** Pure checks of the rules the ledger's proof depends on; no database needed. */
class LedgerRulesTest {

    /** Amounts are parsed to exact integer cents and anything ambiguous is rejected. */
    @Test
    void parsesMoneyExactly() {
        assertEquals(12550, Money.toCents("125.5"));
        assertEquals(1, Money.toCents("0.01"));
        assertEquals(100_000_000, Money.toCents("1000000"));
        for (String bad : new String[] {"0", "0.00", "-5", "1e3", "1.234", "1000000.01", "", " 5", "5."}) {
            assertThrows(ApiException.class, () -> Money.toCents(bad), bad);
        }
    }

    /** Legs must balance, use each account once, and come back in hash order. */
    @Test
    void enforcesDoubleEntry() {
        List<Leg> sorted = LedgerService.checkLegs(List.of(new Leg(9, 500), new Leg(2, -500)));
        assertEquals(List.of(new Leg(2, -500), new Leg(9, 500)), sorted);
        assertThrows(IllegalStateException.class, () -> LedgerService.checkLegs(List.of(new Leg(1, -500), new Leg(2, 499))));
        assertThrows(IllegalStateException.class, () -> LedgerService.checkLegs(List.of(new Leg(1, 0), new Leg(2, 0))));
        assertThrows(IllegalStateException.class, () -> LedgerService.checkLegs(List.of(new Leg(1, 5))));
        assertThrows(ApiException.class, () -> LedgerService.checkLegs(List.of(new Leg(1, -5), new Leg(1, 5))));
    }

    /** The journal hash is deterministic and changes when any field, including a leg amount, changes. */
    @Test
    void journalHashDetectsTampering() {
        List<Leg> legs = List.of(new Leg(1, -500), new Leg(2, 500));
        String a = LedgerService.journalHash(Hashing.GENESIS, 1, "deposit", null, "k", null, 1, "2026-01-01T00:00:00Z", legs);
        String b = LedgerService.journalHash(Hashing.GENESIS, 1, "deposit", null, "k", null, 1, "2026-01-01T00:00:00Z", legs);
        String tampered = LedgerService.journalHash(Hashing.GENESIS, 1, "deposit", null, "k", null, 1, "2026-01-01T00:00:00Z",
                List.of(new Leg(1, -5000), new Leg(2, 5000)));
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertNotEquals(a, tampered);
        // Field boundaries are unambiguous: moving a character between fields changes the hash.
        assertNotEquals(Hashing.chainHash(Hashing.GENESIS, List.of("ab", "c")), Hashing.chainHash(Hashing.GENESIS, List.of("a", "bc")));
    }

    /** The verifier accepts an intact chain and reports an edited row and a broken link. */
    @Test
    void chainVerifierFindsBreaks() {
        String h1 = Hashing.chainHash(Hashing.GENESIS, List.of(1));
        String h2 = Hashing.chainHash(h1, List.of(2));
        ChainVerifier good = new ChainVerifier();
        good.next(1, Hashing.GENESIS, h1, h1);
        good.next(2, h1, h2, h2);
        assertTrue(good.ok());
        assertEquals(h2, good.result(Map.of()).get("headHash"));

        ChainVerifier bad = new ChainVerifier();
        bad.next(1, Hashing.GENESIS, h1, "recomputed-differs");
        bad.next(3, "not-h1", h2, h2);
        assertFalse(bad.ok());
        assertEquals(3L, bad.result(Map.of()).get("problemCount"));
    }

    /** Separation of duties holds and unknown roles get nothing. */
    @Test
    void rolesSeparateDuties() {
        assertFalse(Roles.permissionsFor("admin").contains("money:deposit"));
        assertFalse(Roles.permissionsFor("operator").contains("users:manage"));
        assertTrue(Roles.permissionsFor("auditor").stream().noneMatch(p -> p.startsWith("money:")));
        assertTrue(Roles.permissionsFor("intruder").isEmpty());
        assertTrue(Hashing.constantTimeEquals("same", "same"));
        assertFalse(Hashing.constantTimeEquals("same", "Same"));
    }
}
