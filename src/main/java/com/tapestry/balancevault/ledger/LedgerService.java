package com.tapestry.balancevault.ledger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tapestry.balancevault.audit.AuditService;
import com.tapestry.balancevault.security.AppUser;
import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.ChainVerifier;
import com.tapestry.balancevault.support.Hashing;
import com.tapestry.balancevault.support.Money;

/**
 * Double-entry ledger. Every movement is one journal whose legs sum to zero; balances are always derived from
 * entries, never stored. Journals form a SHA-256 hash chain, rows are append-only (database triggers), mistakes are
 * corrected by reversal journals, and a client requestKey makes every movement safe to retry exactly once.
 */
@Service
public class LedgerService {
    /**
     * Postgres advisory lock taken by every posting. ponytail: one posting at a time across the whole ledger keeps
     * the chain and the balance checks race-free; shard the chain or lock per account if throughput ever matters.
     */
    private static final long CHAIN_LOCK = 4_242_001L;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    /** One side of a journal: positive credits the account, negative debits it. */
    public record Leg(long accountId, long amountCents) {
    }

    public record LegView(long accountId, String accountNumber, long amountCents) {
    }

    public record JournalView(long id, String kind, String memo, String createdAt, String createdBy,
                              Long reversesJournalId, Long reversedById, String hash, List<LegView> legs) {
    }

    /** A posted (or replayed) journal; replayed=true means the requestKey was already used and nothing new moved. */
    public record Posted(JournalView journal, boolean replayed) {
    }

    public record StatementLine(long entryId, long journalId, String kind, String memo, String createdAt,
                                long amountCents, long balanceAfterCents) {
    }

    private record Account(long id, Long ownerId, String kind, String status) {
    }

    private record Head(long id, String hash) {
    }

    /** Wires JDBC, the audit trail and a transaction template for explicit transaction boundaries. */
    public LedgerService(JdbcClient jdbc, AuditService audit, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Pure posting rule: at least two non-zero legs within limits, each account once, summing to exactly zero.
     * Returns the legs sorted by account id, which is the order they are hashed and stored in.
     */
    static List<Leg> checkLegs(List<Leg> legs) {
        if (legs.size() < 2) {
            throw new IllegalStateException("A journal needs at least two legs");
        }
        long sum = 0;
        Set<Long> seen = new HashSet<>();
        for (Leg leg : legs) {
            if (leg.amountCents() == 0 || Math.abs(leg.amountCents()) > Money.MAX_CENTS) {
                throw new IllegalStateException("Leg amount out of range: " + leg.amountCents());
            }
            if (!seen.add(leg.accountId())) {
                throw new ApiException(400, "A transaction cannot use the same account on both sides");
            }
            sum += leg.amountCents();
        }
        if (sum != 0) {
            throw new IllegalStateException("Unbalanced journal: legs sum to " + sum);
        }
        return legs.stream().sorted(Comparator.comparingLong(Leg::accountId)).toList();
    }

    /** The hash of one journal; field order is part of the proof format and must never change. */
    static String journalHash(String prevHash, long id, String kind, String memo, String requestKey, Long reverses,
                              long createdBy, String createdAt, List<Leg> sortedLegs) {
        List<List<Long>> legs = sortedLegs.stream().map(l -> List.of(l.accountId(), l.amountCents())).toList();
        return Hashing.chainHash(prevHash, Arrays.asList(id, kind, memo, requestKey, reverses, createdBy, createdAt, legs));
    }

    /**
     * Runs one movement in a transaction under the chain lock. A requestKey seen before returns the original journal
     * instead of moving money twice. Success is audited in the same transaction; failure in its own.
     */
    private Posted movement(AppUser actor, String requestKey, String action, Supplier<Long> work) {
        try {
            return tx.execute(status -> {
                jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:key)").param("key", CHAIN_LOCK).query(Integer.class).single();
                Optional<Long> previousOwner = jdbc.sql("SELECT created_by FROM journals WHERE idempotency_key = :key")
                        .param("key", requestKey).query(Long.class).optional();
                if (previousOwner.isPresent()) {
                    if (previousOwner.get() != actor.id()) {
                        throw new ApiException(409, "This requestKey was already used");
                    }
                    long existingId = jdbc.sql("SELECT id FROM journals WHERE idempotency_key = :key")
                            .param("key", requestKey).query(Long.class).single();
                    return new Posted(view(existingId), true);
                }
                long journalId = work.get();
                JournalView view = view(journalId);
                audit.record(actor.id(), action, "journal", String.valueOf(journalId), "success",
                        Map.of("legs", view.legs().stream().map(l -> List.of(l.accountNumber(), l.amountCents())).toList()));
                return new Posted(view, false);
            });
        } catch (RuntimeException e) {
            String reason = String.valueOf(e.getMessage());
            audit.recordSeparately(actor.id(), action, null, null, "failure",
                    Map.of("reason", reason.substring(0, Math.min(200, reason.length()))));
            throw e;
        }
    }

    /** Cash in: the system cash account pays the customer account. */
    public Posted deposit(AppUser actor, String requestKey, long accountId, long cents, String memo) {
        return movement(actor, requestKey, "transaction.deposit", () -> {
            Account target = customerAccount(accountId);
            return post("deposit", memo, requestKey, actor.id(), null,
                    List.of(new Leg(cashAccountId(), -cents), new Leg(target.id(), cents)));
        });
    }

    /** Cash out: the customer account pays the system cash account (balance checked in post). */
    public Posted withdraw(AppUser actor, String requestKey, long accountId, long cents, String memo) {
        return movement(actor, requestKey, "transaction.withdraw", () -> {
            Account source = customerAccount(accountId);
            return post("withdrawal", memo, requestKey, actor.id(), null,
                    List.of(new Leg(source.id(), -cents), new Leg(cashAccountId(), cents)));
        });
    }

    /**
     * Customer-to-customer transfer. Without money:transfer_any the source must belong to the actor; a source the
     * actor may not use answers 404, the same as a missing one, so account ids cannot be probed.
     */
    public Posted transfer(AppUser actor, String requestKey, long fromAccountId, String toAccountNumber, long cents,
                           String memo) {
        return movement(actor, requestKey, "transaction.transfer", () -> {
            Account from = account(fromAccountId)
                    .filter(a -> !"system".equals(a.kind()))
                    .filter(a -> actor.can("money:transfer_any") || Long.valueOf(actor.id()).equals(a.ownerId()))
                    .orElseThrow(() -> new ApiException(404, "Source account not found"));
            Account to = jdbc.sql("SELECT id, owner_id, kind, status FROM accounts WHERE account_number = :number")
                    .param("number", toAccountNumber)
                    .query((rs, n) -> new Account(rs.getLong("id"), rs.getObject("owner_id", Long.class),
                            rs.getString("kind"), rs.getString("status")))
                    .optional()
                    .filter(a -> !"system".equals(a.kind()) && "active".equals(a.status()))
                    .orElseThrow(() -> new ApiException(422, "Destination account cannot receive funds"));
            if (to.id() == from.id()) {
                throw new ApiException(400, "Choose a different destination account");
            }
            return post("transfer", memo, requestKey, actor.id(), null,
                    List.of(new Leg(from.id(), -cents), new Leg(to.id(), cents)));
        });
    }

    /** Corrects a journal by posting its exact opposite. History is never edited; each journal can be reversed once. */
    public Posted reverse(AppUser actor, String requestKey, long journalId, String reason) {
        return movement(actor, requestKey, "transaction.reverse", () -> {
            String kind = jdbc.sql("SELECT kind FROM journals WHERE id = :id").param("id", journalId)
                    .query(String.class).optional()
                    .orElseThrow(() -> new ApiException(404, "Transaction not found"));
            if ("reversal".equals(kind)) {
                throw new ApiException(409, "A reversal cannot itself be reversed");
            }
            boolean alreadyReversed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM journals WHERE reverses_journal_id = :id)")
                    .param("id", journalId).query(Boolean.class).single();
            if (alreadyReversed) {
                throw new ApiException(409, "This transaction has already been reversed");
            }
            List<Leg> opposite = jdbc.sql("SELECT account_id, amount_cents FROM entries WHERE journal_id = :id")
                    .param("id", journalId)
                    .query((rs, n) -> new Leg(rs.getLong("account_id"), -rs.getLong("amount_cents")))
                    .list();
            return post("reversal", "Reversal of #" + journalId + ": " + reason, requestKey, actor.id(), journalId, opposite);
        });
    }

    /**
     * Validates and appends one journal to the chain. Caller holds the chain lock inside a transaction, so the
     * balance read and the insert cannot interleave with another posting (no double spend).
     */
    private long post(String kind, String memo, String requestKey, long createdBy, Long reverses, List<Leg> legs) {
        List<Leg> sorted = checkLegs(legs);
        for (Leg leg : sorted) {
            Account account = account(leg.accountId()).orElseThrow(() -> new ApiException(404, "Account not found"));
            if (!"active".equals(account.status())) {
                throw new ApiException(409, "Account is " + account.status() + "; money cannot move through it");
            }
            if (leg.amountCents() < 0 && !"system".equals(account.kind())
                    && balanceOf(account.id()) + leg.amountCents() < 0) {
                throw new ApiException(422, "Insufficient funds");
            }
        }
        Optional<Head> head = jdbc.sql("SELECT id, hash FROM journals ORDER BY id DESC LIMIT 1")
                .query((rs, n) -> new Head(rs.getLong("id"), rs.getString("hash"))).optional();
        long id = head.map(h -> h.id() + 1).orElse(1L);
        String prevHash = head.map(Head::hash).orElse(Hashing.GENESIS);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = journalHash(prevHash, id, kind, memo, requestKey, reverses, createdBy, createdAt.toString(), sorted);
        jdbc.sql("""
                INSERT INTO journals (id, kind, memo, idempotency_key, reverses_journal_id, created_by, created_at, prev_hash, hash)
                VALUES (:id, :kind, :memo, :key, :reverses, :createdBy, :createdAt, :prevHash, :hash)
                """)
                .param("id", id).param("kind", kind).param("memo", memo).param("key", requestKey)
                .param("reverses", reverses).param("createdBy", createdBy)
                .param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .param("prevHash", prevHash).param("hash", hash)
                .update();
        for (Leg leg : sorted) {
            jdbc.sql("INSERT INTO entries (journal_id, account_id, amount_cents) VALUES (:journal, :account, :amount)")
                    .param("journal", id).param("account", leg.accountId()).param("amount", leg.amountCents())
                    .update();
        }
        return id;
    }

    /** Derived balance: the sum of the account's entries. */
    public long balanceOf(long accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM entries WHERE account_id = :id")
                .param("id", accountId).query(Long.class).single();
    }

    /** Newest-first journals with id below `before`; when ownerId is set, only journals touching that owner's accounts. */
    public List<JournalView> list(Long ownerId, Long before, int limit) {
        List<Long> ids = jdbc.sql("""
                SELECT j.id FROM journals j
                WHERE j.id < :before
                  AND (CAST(:ownerId AS BIGINT) IS NULL OR EXISTS (
                        SELECT 1 FROM entries e JOIN accounts a ON a.id = e.account_id
                        WHERE e.journal_id = j.id AND a.owner_id = CAST(:ownerId AS BIGINT)))
                ORDER BY j.id DESC
                LIMIT :limit
                """)
                .param("before", before == null ? Long.MAX_VALUE : before)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(Long.class).list();
        return views(ids);
    }

    /** Newest-first statement lines for one account with the running balance after each entry. */
    public List<StatementLine> statement(long accountId, Long before, int limit) {
        // ponytail: the window runs over the account's whole history per page; store checkpoints if accounts get huge.
        return jdbc.sql("""
                SELECT s.entry_id, s.journal_id, s.kind, s.memo, s.created_at, s.amount_cents, s.balance_after
                FROM (
                  SELECT e.id AS entry_id, j.id AS journal_id, j.kind, j.memo, j.created_at, e.amount_cents,
                         SUM(e.amount_cents) OVER (ORDER BY e.id) AS balance_after
                  FROM entries e JOIN journals j ON j.id = e.journal_id
                  WHERE e.account_id = :id
                ) s
                WHERE s.entry_id < :before
                ORDER BY s.entry_id DESC
                LIMIT :limit
                """)
                .param("id", accountId)
                .param("before", before == null ? Long.MAX_VALUE : before)
                .param("limit", limit)
                .query((rs, n) -> new StatementLine(rs.getLong("entry_id"), rs.getLong("journal_id"), rs.getString("kind"),
                        rs.getString("memo"), AuditService.instant(rs, "created_at"), rs.getLong("amount_cents"),
                        rs.getLong("balance_after")))
                .list();
    }

    /** One journal by id. */
    public Optional<JournalView> find(long journalId) {
        return views(List.of(journalId)).stream().findFirst();
    }

    /**
     * Independent proof of the whole ledger: recomputes every journal hash and chain link, checks each journal
     * balances, finds orphan entries, and confirms all money sums to zero (customer funds = -system cash).
     * ponytail: loads every leg into memory; stream by journal range if the ledger grows to millions of entries.
     */
    public Map<String, Object> verify() {
        Map<Long, List<Leg>> legsByJournal = new HashMap<>();
        jdbc.sql("SELECT journal_id, account_id, amount_cents FROM entries ORDER BY journal_id, account_id")
                .query((RowCallbackHandler) rs -> legsByJournal
                        .computeIfAbsent(rs.getLong("journal_id"), k -> new ArrayList<>())
                        .add(new Leg(rs.getLong("account_id"), rs.getLong("amount_cents"))));
        long entryCount = legsByJournal.values().stream().mapToLong(List::size).sum();
        ChainVerifier chain = new ChainVerifier();
        jdbc.sql("""
                SELECT id, kind, memo, idempotency_key, reverses_journal_id, created_by, created_at, prev_hash, hash
                FROM journals ORDER BY id
                """)
                .query((RowCallbackHandler) rs -> {
                    long id = rs.getLong("id");
                    List<Leg> legs = Optional.ofNullable(legsByJournal.remove(id)).orElse(List.of());
                    long sum = legs.stream().mapToLong(Leg::amountCents).sum();
                    if (legs.size() < 2 || sum != 0) {
                        chain.problem("journal " + id + ": legs do not balance (" + legs.size() + " legs, sum " + sum + ")");
                    }
                    chain.next(id, rs.getString("prev_hash"), rs.getString("hash"), journalHash(rs.getString("prev_hash"), id,
                            rs.getString("kind"), rs.getString("memo"), rs.getString("idempotency_key"),
                            rs.getObject("reverses_journal_id", Long.class), rs.getLong("created_by"),
                            AuditService.instant(rs, "created_at"), legs));
                });
        legsByJournal.keySet().forEach(orphan -> chain.problem("entries reference missing journal " + orphan));
        long total = jdbc.sql("SELECT COALESCE(SUM(amount_cents), 0) FROM entries").query(Long.class).single();
        if (total != 0) {
            chain.problem("all entries should sum to 0 but sum to " + total);
        }
        return chain.result(Map.of("entries", entryCount, "totalCents", total));
    }

    /** Loads journals with their legs and reversal links: two queries regardless of how many ids. */
    private List<JournalView> views(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LegView>> legs = new HashMap<>();
        jdbc.sql("""
                SELECT e.journal_id, e.account_id, a.account_number, e.amount_cents
                FROM entries e JOIN accounts a ON a.id = e.account_id
                WHERE e.journal_id IN (:ids)
                ORDER BY e.journal_id, e.account_id
                """)
                .param("ids", ids)
                .query((RowCallbackHandler) rs -> legs.computeIfAbsent(rs.getLong("journal_id"), k -> new ArrayList<>())
                        .add(new LegView(rs.getLong("account_id"), rs.getString("account_number"), rs.getLong("amount_cents"))));
        return jdbc.sql("""
                SELECT j.id, j.kind, j.memo, j.created_at, u.username AS created_by, j.reverses_journal_id,
                       r.id AS reversed_by, j.hash
                FROM journals j
                JOIN users u ON u.id = j.created_by
                LEFT JOIN journals r ON r.reverses_journal_id = j.id
                WHERE j.id IN (:ids)
                ORDER BY j.id DESC
                """)
                .param("ids", ids)
                .query((rs, n) -> new JournalView(rs.getLong("id"), rs.getString("kind"), rs.getString("memo"),
                        AuditService.instant(rs, "created_at"), rs.getString("created_by"),
                        rs.getObject("reverses_journal_id", Long.class), rs.getObject("reversed_by", Long.class),
                        rs.getString("hash"), legs.getOrDefault(rs.getLong("id"), List.of())))
                .list();
    }

    /** One journal as the API returns it (must exist). */
    private JournalView view(long journalId) {
        return find(journalId).orElseThrow(() -> new IllegalStateException("Journal " + journalId + " vanished"));
    }

    /** Any account by id. */
    private Optional<Account> account(long accountId) {
        return jdbc.sql("SELECT id, owner_id, kind, status FROM accounts WHERE id = :id")
                .param("id", accountId)
                .query((rs, n) -> new Account(rs.getLong("id"), rs.getObject("owner_id", Long.class),
                        rs.getString("kind"), rs.getString("status")))
                .optional();
    }

    /** A customer (non-system) account, or 404. */
    private Account customerAccount(long accountId) {
        return account(accountId).filter(a -> !"system".equals(a.kind()))
                .orElseThrow(() -> new ApiException(404, "Account not found"));
    }

    /** The single system cash account created by the migration. */
    private long cashAccountId() {
        return jdbc.sql("SELECT id FROM accounts WHERE kind = 'system'").query(Long.class).single();
    }
}
