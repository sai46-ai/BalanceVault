package com.tapestry.balancevault.security;

import java.util.Arrays;
import java.util.Set;

/** The signed-in user, rebuilt from the database on every request by SessionAuthFilter (never trusted from the client). */
public record AppUser(long id, String username, String displayName, String role, Set<String> permissions) {

    /** Builds the principal with the role's current permissions. */
    public static AppUser of(long id, String username, String displayName, String role) {
        return new AppUser(id, username, displayName, role, Roles.permissionsFor(role));
    }

    /** True when the user holds this permission. */
    public boolean can(String permission) {
        return permissions.contains(permission);
    }

    /** True when the user holds at least one of the permissions. */
    public boolean canAny(String... anyOf) {
        return Arrays.stream(anyOf).anyMatch(permissions::contains);
    }
}
