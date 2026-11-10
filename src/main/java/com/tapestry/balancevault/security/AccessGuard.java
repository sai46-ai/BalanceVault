package com.tapestry.balancevault.security;

import org.springframework.stereotype.Component;

import com.tapestry.balancevault.audit.AuditService;
import com.tapestry.balancevault.support.ApiException;

/** Explicit permission check used at the top of every protected controller method. Denials are audited. */
@Component
public class AccessGuard {
    private final AuditService audit;

    /** Wires the audit trail used to record denials. */
    public AccessGuard(AuditService audit) {
        this.audit = audit;
    }

    /** Passes when the user holds any of the permissions; otherwise records the denial and throws 403. */
    public void require(AppUser user, String... anyOf) {
        if (!user.canAny(anyOf)) {
            audit.recordSeparately(user.id(), "access.denied", "permission", String.join("|", anyOf), "denied", null);
            throw new ApiException(403, "You do not have permission for this action");
        }
    }
}
