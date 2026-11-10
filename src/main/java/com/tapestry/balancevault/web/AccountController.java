package com.tapestry.balancevault.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tapestry.balancevault.audit.AuditService;
import com.tapestry.balancevault.ledger.LedgerService;
import com.tapestry.balancevault.security.AccessGuard;
import com.tapestry.balancevault.security.AppUser;
import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.Hashing;

/**
 * Accounts. Customers only ever see their own (another customer's account answers 404, not 403, so ids cannot be
 * probed); staff with accounts:read_all see every account including the system cash account.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final AccessGuard guard;
    private final LedgerService ledger;
    private final TransactionTemplate tx;

    public record OpenRequest(
            @NotNull @Positive Long ownerId,
            @NotNull @Pattern(regexp = "^(checking|savings)$", message = "must be checking or savings") String kind,
            @Size(max = 40) @Pattern(regexp = Inputs.FREE_TEXT, message = "contains invalid characters") String nickname) {
    }

    public record StatusRequest(
            @NotNull @Pattern(regexp = "^(active|frozen|closed)$", message = "must be active, frozen or closed") String status,
            @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = Inputs.FREE_TEXT, message = "contains invalid characters") String reason) {
    }

    public record AccountView(long id, String accountNumber, String kind, String nickname, String status,
                              String createdAt, Long ownerId, String ownerName, long balanceCents) {
    }

    /** Wires dependencies. */
    public AccountController(JdbcClient jdbc, AuditService audit, AccessGuard guard, LedgerService ledger,
                             TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.guard = guard;
        this.ledger = ledger;
        this.tx = tx;
    }

    /** Accounts the user may see, with derived balances. */
    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AppUser user) {
        guard.require(user, "accounts:read_all", "accounts:read_own");
        return Map.of("success", true, "accounts", visible(user, null));
    }

    /** One visible account. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        guard.require(user, "accounts:read_all", "accounts:read_own");
        return Map.of("success", true, "account", visibleOne(user, id));
    }

    /** Newest-first statement with running balance; keyset paging via `before` (an entry id). */
    @GetMapping("/{id}/statement")
    public Map<String, Object> statement(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                         @RequestParam(required = false) Long before,
                                         @RequestParam(required = false) Integer limit) {
        guard.require(user, "accounts:read_all", "accounts:read_own");
        AccountView account = visibleOne(user, id);
        return Map.of("success", true, "account", account, "lines", ledger.statement(id, before, Inputs.limit(limit)));
    }

    /** Opens a checking or savings account for an active customer, with a random BV + 10 digit number. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> open(@AuthenticationPrincipal AppUser user, @Valid @RequestBody OpenRequest body) {
        guard.require(user, "accounts:open");
        String nickname = Inputs.optionalText(body.nickname());
        Long id = tx.execute(status -> {
            boolean ownerOk = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id AND role = 'customer' AND status = 'active')")
                    .param("id", body.ownerId()).query(Boolean.class).single();
            if (!ownerOk) {
                throw new ApiException(422, "Accounts can only be opened for an active customer");
            }
            String number = newAccountNumber();
            long accountId = jdbc.sql("""
                    INSERT INTO accounts (account_number, owner_id, kind, nickname, created_by)
                    VALUES (:number, :owner, :kind, :nickname, :by) RETURNING id
                    """)
                    .param("number", number).param("owner", body.ownerId()).param("kind", body.kind())
                    .param("nickname", nickname).param("by", user.id())
                    .query(Long.class).single();
            audit.record(user.id(), "account.open", "account", String.valueOf(accountId), "success",
                    Map.of("kind", body.kind(), "ownerId", body.ownerId()));
            return accountId;
        });
        return ResponseEntity.status(201).body(Map.of("success", true, "account", visibleOne(user, id)));
    }

    /**
     * Freeze, unfreeze or close. Closing needs a zero balance and is final; the system cash account cannot change.
     * The row is locked so a concurrent change cannot slip between the checks and the update.
     */
    @PostMapping("/{id}/status")
    public Map<String, Object> setStatus(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                         @Valid @RequestBody StatusRequest body) {
        guard.require(user, "accounts:status");
        String reason = body.reason().trim();
        tx.executeWithoutResult(status -> {
            String[] row = jdbc.sql("SELECT kind, status FROM accounts WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query((rs, n) -> new String[] {rs.getString("kind"), rs.getString("status")})
                    .optional()
                    .orElseThrow(() -> new ApiException(404, "Account not found"));
            String kind = row[0];
            String current = row[1];
            if ("system".equals(kind)) {
                throw new ApiException(400, "The system cash account cannot change status");
            }
            if ("closed".equals(current)) {
                throw new ApiException(409, "Closed accounts cannot be reopened");
            }
            if (current.equals(body.status())) {
                throw new ApiException(400, "Account is already " + current);
            }
            if ("closed".equals(body.status()) && ledger.balanceOf(id) != 0) {
                throw new ApiException(409, "Only an account with a zero balance can be closed");
            }
            jdbc.sql("UPDATE accounts SET status = :status WHERE id = :id").param("status", body.status()).param("id", id).update();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("from", current);
            detail.put("to", body.status());
            detail.put("reason", reason);
            audit.record(user.id(), "account.status", "account", String.valueOf(id), "success", detail);
        });
        return Map.of("success", true, "account", visibleOne(user, id));
    }

    /** Accounts visible to the user, optionally just one id. */
    private List<AccountView> visible(AppUser user, Long id) {
        return jdbc.sql("""
                SELECT a.id, a.account_number, a.kind, a.nickname, a.status, a.created_at, a.owner_id,
                       u.display_name AS owner_name,
                       (SELECT COALESCE(SUM(e.amount_cents), 0) FROM entries e WHERE e.account_id = a.id) AS balance_cents
                FROM accounts a LEFT JOIN users u ON u.id = a.owner_id
                WHERE (:all OR a.owner_id = :uid)
                  AND (CAST(:id AS BIGINT) IS NULL OR a.id = CAST(:id AS BIGINT))
                ORDER BY (a.kind = 'system') DESC, a.id
                LIMIT 500
                """)
                .param("all", user.can("accounts:read_all"))
                .param("uid", user.id())
                .param("id", id)
                .query((rs, n) -> new AccountView(rs.getLong("id"), rs.getString("account_number"), rs.getString("kind"),
                        rs.getString("nickname"), rs.getString("status"), AuditService.instant(rs, "created_at"),
                        rs.getObject("owner_id", Long.class), rs.getString("owner_name"), rs.getLong("balance_cents")))
                .list();
    }

    /** One visible account, or 404 (also for accounts that exist but belong to someone else). */
    private AccountView visibleOne(AppUser user, long id) {
        return visible(user, id).stream().findFirst().orElseThrow(() -> new ApiException(404, "Account not found"));
    }

    /** A random unused account number; collisions are astronomically rare but still checked. */
    private String newAccountNumber() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "BV" + Hashing.randomDigits(10);
            boolean taken = jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE account_number = :n)")
                    .param("n", candidate).query(Boolean.class).single();
            if (!taken) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate an account number");
    }
}
