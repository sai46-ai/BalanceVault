package com.tapestry.balancevault.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for RBAC. Routes check permissions, never role names, so changing what a role may
 * do is a one-line edit here. Separation of duties: operators move money but cannot manage staff; auditors read
 * everything but change nothing; admins manage people but do not post transactions.
 */
public final class Roles {
    public static final List<String> ALL = List.of("admin", "operator", "auditor", "customer");
    public static final List<String> STAFF = List.of("admin", "operator", "auditor");

    private static final Map<String, Set<String>> PERMISSIONS = Map.of(
            "admin", Set.of("users:manage", "customers:onboard", "accounts:read_all", "accounts:open",
                    "accounts:status", "transactions:read_all", "audit:read", "ledger:verify"),
            "operator", Set.of("customers:onboard", "accounts:read_all", "accounts:open", "accounts:status",
                    "money:deposit", "money:withdraw", "money:transfer_any", "money:reverse",
                    "transactions:read_all"),
            "auditor", Set.of("accounts:read_all", "transactions:read_all", "audit:read", "ledger:verify"),
            "customer", Set.of("accounts:read_own", "money:transfer_own", "transactions:read_own"));

    private Roles() {
    }

    /** Permissions granted to a role; unknown roles get none (deny by default). */
    public static Set<String> permissionsFor(String role) {
        return PERMISSIONS.getOrDefault(role, Set.of());
    }
}
