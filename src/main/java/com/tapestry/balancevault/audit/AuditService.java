package com.tapestry.balancevault.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.tapestry.balancevault.support.ChainVerifier;
import com.tapestry.balancevault.support.Hashing;

/**
 * Append-only, hash-chained audit trail. Each row's hash covers its content and the previous row's hash, so editing,
 * deleting or reordering any row breaks verify(). Details must never contain passwords, tokens or emails.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger("audit");
    /** Postgres advisory lock that serialises appends so ids and prev_hash links never fork. */
    private static final long CHAIN_LOCK = 4_242_002L;

    private final JdbcClient jdbc;
    private final TransactionTemplate requiresNew;

    /** One audit row as the API returns it. */
    public record AuditRow(long id, String at, Long actorId, String actorUsername, String action, String targetType,
                           String targetId, String outcome, String detail, String requestId, String hash) {
    }

    private record Head(long id, String hash) {
    }

    /** Wires JDBC and a REQUIRES_NEW template for audit rows that must survive a rolled-back operation. */
    public AuditService(JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Appends inside the caller's transaction, so the audit row commits or rolls back with the operation it describes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorId, String action, String targetType, String targetId, String outcome,
                       Map<String, ?> detail) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:key)").param("key", CHAIN_LOCK).query(Integer.class).single();
        Optional<Head> head = jdbc.sql("SELECT id, hash FROM audit_log ORDER BY id DESC LIMIT 1")
                .query((rs, n) -> new Head(rs.getLong("id"), rs.getString("hash")))
                .optional();
        long id = head.map(h -> h.id() + 1).orElse(1L);
        String prevHash = head.map(Head::hash).orElse(Hashing.GENESIS);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String detailJson = detail == null ? null : Hashing.toJson(detail);
        String requestId = MDC.get("requestId");
        String hash = hashOf(prevHash, id, at.toString(), actorId, action, targetType, targetId, outcome, detailJson, requestId);
        jdbc.sql("""
                INSERT INTO audit_log (id, at, actor_id, action, target_type, target_id, outcome, detail, request_id, prev_hash, hash)
                VALUES (:id, :at, :actorId, :action, :targetType, :targetId, :outcome, :detail, :requestId, :prevHash, :hash)
                """)
                .param("id", id)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("actorId", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("outcome", outcome)
                .param("detail", detailJson)
                .param("requestId", requestId)
                .param("prevHash", prevHash)
                .param("hash", hash)
                .update();
        log.info("action={} outcome={} actor={} targetType={} targetId={}", action, outcome, actorId, targetType, targetId);
    }

    /** Appends in its own transaction: for failures and denials whose operation is about to roll back or never ran. */
    public void recordSeparately(Long actorId, String action, String targetType, String targetId, String outcome,
                                 Map<String, ?> detail) {
        requiresNew.executeWithoutResult(status -> record(actorId, action, targetType, targetId, outcome, detail));
    }

    /** Newest-first page of audit rows with id below `before` (keyset paging). */
    public List<AuditRow> list(Long before, int limit) {
        return jdbc.sql("""
                SELECT a.id, a.at, a.actor_id, u.username AS actor_username, a.action, a.target_type, a.target_id,
                       a.outcome, a.detail, a.request_id, a.hash
                FROM audit_log a LEFT JOIN users u ON u.id = a.actor_id
                WHERE a.id < :before
                ORDER BY a.id DESC
                LIMIT :limit
                """)
                .param("before", before == null ? Long.MAX_VALUE : before)
                .param("limit", limit)
                .query((rs, n) -> new AuditRow(rs.getLong("id"), instant(rs, "at"), rs.getObject("actor_id", Long.class),
                        rs.getString("actor_username"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("outcome"), rs.getString("detail"),
                        rs.getString("request_id"), rs.getString("hash")))
                .list();
    }

    /** Recomputes every hash and link in the audit chain. ponytail: one pass over all rows; checkpoint signed heads if it grows to millions. */
    public Map<String, Object> verify() {
        ChainVerifier chain = new ChainVerifier();
        jdbc.sql("""
                SELECT id, at, actor_id, action, target_type, target_id, outcome, detail, request_id, prev_hash, hash
                FROM audit_log ORDER BY id
                """)
                .query((RowCallbackHandler) rs -> chain.next(rs.getLong("id"), rs.getString("prev_hash"), rs.getString("hash"),
                        hashOf(rs.getString("prev_hash"), rs.getLong("id"), instant(rs, "at"), rs.getObject("actor_id", Long.class),
                                rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"),
                                rs.getString("outcome"), rs.getString("detail"), rs.getString("request_id"))));
        return chain.result(Map.of());
    }

    /** The hash of one audit row; field order is part of the format and must never change. */
    private static String hashOf(String prevHash, long id, String at, Long actorId, String action, String targetType,
                                 String targetId, String outcome, String detail, String requestId) {
        return Hashing.chainHash(prevHash, Arrays.asList(id, at, actorId, action, targetType, targetId, outcome, detail, requestId));
    }

    /** Reads a TIMESTAMPTZ column as the same ISO-8601 instant string that was hashed on insert. */
    public static String instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }
}
