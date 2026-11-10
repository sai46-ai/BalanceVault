package com.tapestry.balancevault.web;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tapestry.balancevault.ledger.LedgerService;
import com.tapestry.balancevault.security.AccessGuard;
import com.tapestry.balancevault.security.AppUser;
import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.Money;

/**
 * Money movement. Every write carries a client-generated requestKey (UUID): retrying with the same key returns the
 * original journal (200, replayed=true) instead of moving money twice; a new movement answers 201.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private static final String KEY_MESSAGE = "must be a lower-case UUID";

    private final LedgerService ledger;
    private final AccessGuard guard;
    private final JdbcClient jdbc;

    public record CashRequest(
            @NotNull @Positive Long accountId,
            @NotNull @Size(max = 16) String amount,
            @Size(max = 140) @Pattern(regexp = Inputs.FREE_TEXT, message = "contains invalid characters") String memo,
            @NotNull @Pattern(regexp = Inputs.REQUEST_KEY, message = KEY_MESSAGE) String requestKey) {
    }

    public record TransferRequest(
            @NotNull @Positive Long fromAccountId,
            @NotNull @Pattern(regexp = Inputs.ACCOUNT_NUMBER, message = "must look like BV0123456789") String toAccountNumber,
            @NotNull @Size(max = 16) String amount,
            @Size(max = 140) @Pattern(regexp = Inputs.FREE_TEXT, message = "contains invalid characters") String memo,
            @NotNull @Pattern(regexp = Inputs.REQUEST_KEY, message = KEY_MESSAGE) String requestKey) {
    }

    public record ReverseRequest(
            @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = Inputs.FREE_TEXT, message = "contains invalid characters") String reason,
            @NotNull @Pattern(regexp = Inputs.REQUEST_KEY, message = KEY_MESSAGE) String requestKey) {
    }

    /** Wires dependencies. */
    public TransactionController(LedgerService ledger, AccessGuard guard, JdbcClient jdbc) {
        this.ledger = ledger;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    /** Cash deposit into a customer account (operators). */
    @PostMapping("/deposit")
    public ResponseEntity<Map<String, Object>> deposit(@AuthenticationPrincipal AppUser user, @Valid @RequestBody CashRequest body) {
        guard.require(user, "money:deposit");
        return respond(ledger.deposit(user, body.requestKey(), body.accountId(), Money.toCents(body.amount()),
                Inputs.optionalText(body.memo())));
    }

    /** Cash withdrawal from a customer account (operators); fails on insufficient funds. */
    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@AuthenticationPrincipal AppUser user, @Valid @RequestBody CashRequest body) {
        guard.require(user, "money:withdraw");
        return respond(ledger.withdraw(user, body.requestKey(), body.accountId(), Money.toCents(body.amount()),
                Inputs.optionalText(body.memo())));
    }

    /** Transfer to another customer account by number. Customers may only send from their own accounts. */
    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transfer(@AuthenticationPrincipal AppUser user, @Valid @RequestBody TransferRequest body) {
        guard.require(user, "money:transfer_any", "money:transfer_own");
        return respond(ledger.transfer(user, body.requestKey(), body.fromAccountId(), body.toAccountNumber(),
                Money.toCents(body.amount()), Inputs.optionalText(body.memo())));
    }

    /** Posts the exact opposite of a journal (operators). The original stays in history. */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<Map<String, Object>> reverse(@AuthenticationPrincipal AppUser user, @PathVariable long id,
                                                       @Valid @RequestBody ReverseRequest body) {
        guard.require(user, "money:reverse");
        return respond(ledger.reverse(user, body.requestKey(), id, body.reason().trim()));
    }

    /** Newest-first journals; customers only see journals touching their own accounts. Keyset paging via `before`. */
    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal AppUser user,
                                    @RequestParam(required = false) Long before,
                                    @RequestParam(required = false) Integer limit) {
        guard.require(user, "transactions:read_all", "transactions:read_own");
        Long ownerFilter = user.can("transactions:read_all") ? null : user.id();
        return Map.of("success", true, "transactions", ledger.list(ownerFilter, before, Inputs.limit(limit)));
    }

    /** One journal with its legs and hash; 404 when it does not exist or is not the customer's. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        guard.require(user, "transactions:read_all", "transactions:read_own");
        if (!user.can("transactions:read_all")) {
            boolean mine = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM entries e JOIN accounts a ON a.id = e.account_id
                                   WHERE e.journal_id = :journal AND a.owner_id = :uid)
                    """)
                    .param("journal", id).param("uid", user.id()).query(Boolean.class).single();
            if (!mine) {
                throw new ApiException(404, "Transaction not found");
            }
        }
        LedgerService.JournalView journal = ledger.find(id).orElseThrow(() -> new ApiException(404, "Transaction not found"));
        return Map.of("success", true, "transaction", journal);
    }

    /** 201 for a new journal, 200 for an idempotent replay. */
    private static ResponseEntity<Map<String, Object>> respond(LedgerService.Posted posted) {
        return ResponseEntity.status(posted.replayed() ? 200 : 201)
                .body(Map.of("success", true, "replayed", posted.replayed(), "transaction", posted.journal()));
    }
}
