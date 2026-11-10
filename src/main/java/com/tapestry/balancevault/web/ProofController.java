package com.tapestry.balancevault.web;

import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tapestry.balancevault.audit.AuditService;
import com.tapestry.balancevault.ledger.LedgerService;
import com.tapestry.balancevault.security.AccessGuard;
import com.tapestry.balancevault.security.AppUser;

/** Read-only proof surface for auditors and admins: the audit trail and independent verification of both chains. */
@RestController
@RequestMapping("/api")
public class ProofController {
    private final AuditService audit;
    private final LedgerService ledger;
    private final AccessGuard guard;

    /** Wires dependencies. */
    public ProofController(AuditService audit, LedgerService ledger, AccessGuard guard) {
        this.audit = audit;
        this.ledger = ledger;
        this.guard = guard;
    }

    /** Newest-first audit rows; keyset paging via `before` (an audit id). */
    @GetMapping("/audit")
    public Map<String, Object> auditLog(@AuthenticationPrincipal AppUser user,
                                        @RequestParam(required = false) Long before,
                                        @RequestParam(required = false) Integer limit) {
        guard.require(user, "audit:read");
        return Map.of("success", true, "events", audit.list(before, Inputs.limit(limit)));
    }

    /**
     * Recomputes every journal and audit hash, every chain link, each journal's balance and the ledger total.
     * The head hashes can be written down or published: any later edit to history changes them.
     */
    @GetMapping("/ledger/verify")
    public Map<String, Object> verify(@AuthenticationPrincipal AppUser user) {
        guard.require(user, "ledger:verify");
        Map<String, Object> ledgerProof = ledger.verify();
        Map<String, Object> auditProof = audit.verify();
        boolean ok = Boolean.TRUE.equals(ledgerProof.get("ok")) && Boolean.TRUE.equals(auditProof.get("ok"));
        audit.recordSeparately(user.id(), "ledger.verify", null, null, ok ? "success" : "failure",
                Map.of("ledgerOk", ledgerProof.get("ok"), "auditOk", auditProof.get("ok")));
        return Map.of("success", true, "ok", ok, "ledger", ledgerProof, "audit", auditProof);
    }
}
