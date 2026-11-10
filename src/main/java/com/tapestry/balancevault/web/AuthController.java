package com.tapestry.balancevault.web;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tapestry.balancevault.audit.AuditService;
import com.tapestry.balancevault.security.AppUser;
import com.tapestry.balancevault.security.LoginRateLimiter;
import com.tapestry.balancevault.security.SessionAuthFilter;
import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.Hashing;

/** Login, logout, session info and invitation-based onboarding. */
@RestController
@RequestMapping("/api")
public class AuthController {
    private static final int MAX_FAILED_LOGINS = 5;
    private static final int LOCKOUT_MINUTES = 15;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final LoginRateLimiter limiter;
    private final TransactionTemplate tx;

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record TokenRequest(@NotNull @Pattern(regexp = Inputs.TOKEN, message = "is not a valid invitation token") String token) {
    }

    public record AcceptRequest(
            @NotNull @Pattern(regexp = Inputs.TOKEN, message = "is not a valid invitation token") String token,
            @NotNull @Pattern(regexp = Inputs.USERNAME, message = "must be 3-32 lower-case letters, digits, . _ or -") String username,
            @NotNull @Pattern(regexp = Inputs.PASSWORD, message = "must be 12-128 characters with upper, lower, digit and symbol") String password) {
    }

    public record UserView(long id, String username, String displayName, String role, Set<String> permissions) {
        static UserView of(AppUser u) {
            return new UserView(u.id(), u.username(), u.displayName(), u.role(), u.permissions());
        }
    }

    private record UserRow(long id, String username, String displayName, String role, String status, String password,
                           boolean locked, int failedLogins) {
    }

    private record Invite(long id, String displayName, String role, String expiresAt) {
    }

    /** Wires the database, audit trail and per-IP limiter. */
    public AuthController(JdbcClient jdbc, AuditService audit, LoginRateLimiter limiter, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.limiter = limiter;
        this.tx = tx;
    }

    /**
     * Password login with per-IP rate limiting and per-account lockout (5 failures -> 15 minutes). Every outcome
     * returns the same message so usernames cannot be enumerated. A fresh session id is issued (no fixation).
     */
    @PostMapping("/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        String username = body.username().trim().toLowerCase(Locale.ROOT);
        Optional<UserRow> found = jdbc.sql("""
                SELECT id, username, display_name, role, status, password, failed_logins,
                       (locked_until IS NOT NULL AND locked_until > now()) AS locked
                FROM users WHERE username = :username
                """)
                .param("username", username)
                .query((rs, n) -> new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("role"), rs.getString("status"), rs.getString("password"), rs.getBoolean("locked"),
                        rs.getInt("failed_logins")))
                .optional();
        // Compare even when the user is unknown so response timing does not reveal which usernames exist.
        boolean passwordOk = Hashing.constantTimeEquals(body.password(), found.map(UserRow::password).orElse(""));
        if (found.isEmpty() || !"active".equals(found.get().status())) {
            audit.recordSeparately(found.map(UserRow::id).orElse(null), "auth.login", null, null, "failure",
                    Map.of("reason", found.isEmpty() ? "unknown_user" : "disabled"));
            throw new ApiException(401, "Invalid username or password");
        }
        UserRow user = found.get();
        if (user.locked()) {
            audit.recordSeparately(user.id(), "auth.login", "user", String.valueOf(user.id()), "denied", Map.of("reason", "locked"));
            throw new ApiException(423, "Account temporarily locked after repeated failed sign-ins. Try again later.");
        }
        if (!passwordOk) {
            boolean nowLocked = jdbc.sql("""
                    UPDATE users SET
                      failed_logins = CASE WHEN failed_logins + 1 >= :max THEN 0 ELSE failed_logins + 1 END,
                      locked_until  = CASE WHEN failed_logins + 1 >= :max THEN CAST(:until AS TIMESTAMPTZ) ELSE locked_until END
                    WHERE id = :id
                    RETURNING (locked_until IS NOT NULL AND locked_until > now()) AS locked
                    """)
                    .param("max", MAX_FAILED_LOGINS)
                    .param("until", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(LOCKOUT_MINUTES))
                    .param("id", user.id())
                    .query(Boolean.class).single();
            audit.recordSeparately(user.id(), "auth.login", "user", String.valueOf(user.id()), "failure",
                    Map.of("reason", "bad_password", "lockedNow", nowLocked));
            throw new ApiException(401, "Invalid username or password");
        }
        jdbc.sql("UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = :id").param("id", user.id()).update();
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        request.getSession(true).setAttribute(SessionAuthFilter.SESSION_USER_ID, user.id());
        audit.recordSeparately(user.id(), "auth.login", "user", String.valueOf(user.id()), "success", null);
        AppUser principal = AppUser.of(user.id(), user.username(), user.displayName(), user.role());
        return Map.of("success", true, "user", UserView.of(principal));
    }

    /** Ends the server-side session (CSRF-protected). */
    @PostMapping("/auth/logout")
    public Map<String, Object> logout(@AuthenticationPrincipal AppUser user, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        audit.recordSeparately(user.id(), "auth.logout", "user", String.valueOf(user.id()), "success", null);
        return Map.of("success", true);
    }

    /** The signed-in user and the CSRF token the client must send as X-CSRF-TOKEN on every write. */
    @GetMapping("/auth/me")
    public Map<String, Object> me(@AuthenticationPrincipal AppUser user, CsrfToken csrf) {
        return Map.of("success", true, "user", UserView.of(user), "csrfToken", csrf.getToken());
    }

    /** Shows who an invitation is for, so the invitee can confirm before choosing credentials. */
    @PostMapping("/onboarding/lookup")
    public Map<String, Object> lookup(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        Invite invite = openInvite(body.token())
                .orElseThrow(() -> new ApiException(404, "This invitation is invalid, expired or already used"));
        return Map.of("success", true, "invitation", invite);
    }

    /** Accepts an invitation: creates the user with the invited name and role. Single use, atomic. */
    @PostMapping("/onboarding/accept")
    public ResponseEntity<Map<String, Object>> accept(@Valid @RequestBody AcceptRequest body, HttpServletRequest request) {
        limiter.check(request.getRemoteAddr());
        if (body.password().toLowerCase(Locale.ROOT).contains(body.username())) {
            throw new ApiException(400, "Invalid input: password must not contain the username");
        }
        try {
            Long userId = tx.execute(status -> {
                Invite invite = openInvite(body.token())
                        .orElseThrow(() -> new ApiException(404, "This invitation is invalid, expired or already used"));
                boolean taken = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :u)")
                        .param("u", body.username()).query(Boolean.class).single();
                if (taken) {
                    throw new ApiException(409, "That username is taken");
                }
                long id = jdbc.sql("""
                        INSERT INTO users (username, display_name, password, role, created_by)
                        SELECT :u, display_name, :p, role, created_by FROM invitations WHERE id = :inviteId
                        RETURNING id
                        """)
                        .param("u", body.username()).param("p", body.password()).param("inviteId", invite.id())
                        .query(Long.class).single();
                jdbc.sql("UPDATE invitations SET accepted_at = now(), accepted_by = :uid WHERE id = :id")
                        .param("uid", id).param("id", invite.id()).update();
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("role", invite.role());
                detail.put("invitationId", invite.id());
                audit.record(id, "onboarding.accept", "user", String.valueOf(id), "success", detail);
                return id;
            });
            return ResponseEntity.status(201).body(Map.of("success", true, "userId", userId));
        } catch (ApiException e) {
            audit.recordSeparately(null, "onboarding.accept", null, null, "failure", Map.of("reason", e.getMessage()));
            throw e;
        }
    }

    /**
     * An unexpired, unused, unrevoked invitation by token (only its SHA-256 is stored). FOR UPDATE makes two
     * concurrent accepts of the same token serialise, so only one can succeed; outside a transaction it is a no-op.
     */
    private Optional<Invite> openInvite(String token) {
        return jdbc.sql("""
                SELECT id, display_name, role, expires_at FROM invitations
                WHERE token_hash = :hash AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                FOR UPDATE
                """)
                .param("hash", Hashing.sha256Hex(token))
                .query((rs, n) -> new Invite(rs.getLong("id"), rs.getString("display_name"), rs.getString("role"),
                        AuditService.instant(rs, "expires_at")))
                .optional();
    }
}
