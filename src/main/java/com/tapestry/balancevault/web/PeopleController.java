package com.tapestry.balancevault.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tapestry.balancevault.audit.AuditService;
import com.tapestry.balancevault.security.AccessGuard;
import com.tapestry.balancevault.security.AppUser;
import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.Hashing;

/**
 * Onboarding invitations and user administration. Operators may invite customers only; staff invitations and every
 * role/status change need users:manage (admin).
 */
@RestController
@RequestMapping("/api")
public class PeopleController {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final AccessGuard guard;
    private final TransactionTemplate tx;
    private final int inviteTtlHours;

    public record InviteRequest(
            @NotNull @Pattern(regexp = Inputs.DISPLAY_NAME, message = "must be 1-80 letters, spaces, apostrophes, dots or hyphens") String displayName,
            @NotNull @Pattern(regexp = Inputs.ROLE, message = "must be admin, operator, auditor or customer") String role) {
    }

    public record UserUpdate(
            @Pattern(regexp = Inputs.ROLE, message = "must be admin, operator, auditor or customer") String role,
            @Pattern(regexp = "^(active|disabled)$", message = "must be active or disabled") String status) {
    }

    public record InvitationView(long id, String displayName, String role, String createdBy, String createdAt,
                                 String expiresAt, String state) {
    }

    public record UserRow(long id, String username, String displayName, String role, String status, boolean locked,
                          String createdAt) {
    }

    public record CustomerView(long id, String username, String displayName) {
    }

    /** Wires dependencies; INVITE_TTL_HOURS sets how long an invitation stays usable. */
    public PeopleController(JdbcClient jdbc, AuditService audit, AccessGuard guard, TransactionTemplate tx,
                            @Value("${bv.invite-ttl-hours}") int inviteTtlHours) {
        if (inviteTtlHours < 1 || inviteTtlHours > 720) {
            throw new IllegalStateException("INVITE_TTL_HOURS must be between 1 and 720");
        }
        this.jdbc = jdbc;
        this.audit = audit;
        this.guard = guard;
        this.tx = tx;
        this.inviteTtlHours = inviteTtlHours;
    }

    /** Creates a single-use invitation. The token is returned once here and only its hash is stored. */
    @PostMapping("/invitations")
    public ResponseEntity<Map<String, Object>> invite(@AuthenticationPrincipal AppUser user, @Valid @RequestBody InviteRequest body) {
        guard.require(user, "customers:onboard", "users:manage");
        if (!"customer".equals(body.role())) {
            guard.require(user, "users:manage");
        }
        String token = Hashing.randomToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(inviteTtlHours);
        String displayName = body.displayName().trim();
        Long id = tx.execute(status -> {
            long inviteId = jdbc.sql("""
                    INSERT INTO invitations (token_hash, display_name, role, created_by, expires_at)
                    VALUES (:hash, :name, :role, :by, :expires) RETURNING id
                    """)
                    .param("hash", Hashing.sha256Hex(token)).param("name", displayName).param("role", body.role())
                    .param("by", user.id()).param("expires", expiresAt)
                    .query(Long.class).single();
            audit.record(user.id(), "onboarding.invite", "invitation", String.valueOf(inviteId), "success",
                    Map.of("role", body.role()));
            return inviteId;
        });
        Map<String, Object> invitation = new LinkedHashMap<>();
        invitation.put("id", id);
        invitation.put("displayName", displayName);
        invitation.put("role", body.role());
        invitation.put("expiresAt", expiresAt.toInstant().toString());
        return ResponseEntity.status(201).body(Map.of("success", true, "invitation", invitation, "token", token));
    }

    /** Recent invitations; without users:manage only customer invitations are visible. */
    @GetMapping("/invitations")
    public Map<String, Object> invitations(@AuthenticationPrincipal AppUser user) {
        guard.require(user, "customers:onboard", "users:manage");
        List<InvitationView> rows = jdbc.sql("""
                SELECT i.id, i.display_name, i.role, u.username AS created_by, i.created_at, i.expires_at,
                       CASE WHEN i.accepted_at IS NOT NULL THEN 'accepted'
                            WHEN i.revoked_at IS NOT NULL THEN 'revoked'
                            WHEN i.expires_at <= now() THEN 'expired'
                            ELSE 'open' END AS state
                FROM invitations i JOIN users u ON u.id = i.created_by
                WHERE (:all OR i.role = 'customer')
                ORDER BY i.id DESC
                LIMIT 200
                """)
                .param("all", user.can("users:manage"))
                .query((rs, n) -> new InvitationView(rs.getLong("id"), rs.getString("display_name"), rs.getString("role"),
                        rs.getString("created_by"), AuditService.instant(rs, "created_at"),
                        AuditService.instant(rs, "expires_at"), rs.getString("state")))
                .list();
        return Map.of("success", true, "invitations", rows);
    }

    /** Revokes an open invitation so its token stops working. */
    @PostMapping("/invitations/{id}/revoke")
    public Map<String, Object> revoke(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        guard.require(user, "customers:onboard", "users:manage");
        tx.executeWithoutResult(status -> {
            int changed = jdbc.sql("""
                    UPDATE invitations SET revoked_at = now()
                    WHERE id = :id AND accepted_at IS NULL AND revoked_at IS NULL AND (:all OR role = 'customer')
                    """)
                    .param("id", id).param("all", user.can("users:manage")).update();
            if (changed == 0) {
                throw new ApiException(404, "No open invitation with that id");
            }
            audit.record(user.id(), "onboarding.revoke", "invitation", String.valueOf(id), "success", null);
        });
        return Map.of("success", true);
    }

    /** Every user with role, status and lockout state (admin only). */
    @GetMapping("/users")
    public Map<String, Object> users(@AuthenticationPrincipal AppUser user) {
        guard.require(user, "users:manage");
        List<UserRow> rows = jdbc.sql("""
                SELECT id, username, display_name, role, status, created_at,
                       (locked_until IS NOT NULL AND locked_until > now()) AS locked
                FROM users ORDER BY id LIMIT 500
                """)
                .query((rs, n) -> new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("role"), rs.getString("status"), rs.getBoolean("locked"),
                        AuditService.instant(rs, "created_at")))
                .list();
        return Map.of("success", true, "users", rows);
    }

    /**
     * Changes a user's role and/or status. Setting status to active also clears a login lockout. Admins cannot
     * change themselves, so the last admin can never demote or disable their own account by mistake.
     */
    @PatchMapping("/users/{id}")
    public Map<String, Object> updateUser(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                          @Valid @RequestBody UserUpdate body) {
        guard.require(user, "users:manage");
        if (body.role() == null && body.status() == null) {
            throw new ApiException(400, "Invalid input: send role and/or status");
        }
        if (id == user.id()) {
            throw new ApiException(400, "You cannot change your own role or status");
        }
        tx.executeWithoutResult(status -> {
            int changed = jdbc.sql("""
                    UPDATE users SET
                      role = COALESCE(CAST(:role AS VARCHAR), role),
                      status = COALESCE(CAST(:status AS VARCHAR), status),
                      failed_logins = CASE WHEN CAST(:status AS VARCHAR) = 'active' THEN 0 ELSE failed_logins END,
                      locked_until = CASE WHEN CAST(:status AS VARCHAR) = 'active' THEN NULL ELSE locked_until END
                    WHERE id = :id
                    """)
                    .param("role", body.role()).param("status", body.status()).param("id", id).update();
            if (changed == 0) {
                throw new ApiException(404, "User not found");
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            if (body.role() != null) {
                detail.put("role", body.role());
            }
            if (body.status() != null) {
                detail.put("status", body.status());
            }
            audit.record(user.id(), "user.update", "user", String.valueOf(id), "success", detail);
        });
        return Map.of("success", true);
    }

    /** Active customers, for choosing an owner when opening an account. */
    @GetMapping("/customers")
    public Map<String, Object> customers(@AuthenticationPrincipal AppUser user) {
        guard.require(user, "accounts:open");
        List<CustomerView> rows = jdbc.sql("""
                SELECT id, username, display_name FROM users
                WHERE role = 'customer' AND status = 'active'
                ORDER BY display_name, id LIMIT 500
                """)
                .query((rs, n) -> new CustomerView(rs.getLong("id"), rs.getString("username"), rs.getString("display_name")))
                .list();
        return Map.of("success", true, "customers", rows);
    }
}
