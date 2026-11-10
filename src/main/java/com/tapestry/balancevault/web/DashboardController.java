package com.tapestry.balancevault.web;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tapestry.balancevault.security.AccessGuard;
import com.tapestry.balancevault.security.AppUser;

/**
 * Read-only overview numbers for the dashboard, aggregated in SQL. Scoped exactly like the account list:
 * staff with accounts:read_all see every customer account, a customer sees only their own. The system cash
 * account is excluded everywhere so totals mean "money held for customers".
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    /** Days of history the activity chart shows (today included). */
    static final int WINDOW_DAYS = 30;

    private final JdbcClient jdbc;
    private final AccessGuard guard;

    public record Totals(long balanceCents, long active, long frozen, long closed) {
    }

    public record Day(String day, long inflowCents, long outflowCents, long journals) {
    }

    public record KindCount(String kind, long count) {
    }

    /** Wires dependencies. */
    public DashboardController(JdbcClient jdbc, AccessGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    /** Totals, a 30-day daily money-in/money-out series and journal counts by kind, all for visible accounts. */
    @GetMapping
    public Map<String, Object> summary(@AuthenticationPrincipal AppUser user) {
        guard.require(user, "accounts:read_all", "accounts:read_own");
        boolean all = user.can("accounts:read_all");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate first = today.minusDays(WINDOW_DAYS - 1L);
        OffsetDateTime since = first.atStartOfDay().atOffset(ZoneOffset.UTC);

        Totals totals = jdbc.sql("""
                SELECT COALESCE(SUM((SELECT COALESCE(SUM(e.amount_cents), 0) FROM entries e WHERE e.account_id = a.id)), 0) AS balance,
                       COUNT(*) FILTER (WHERE a.status = 'active') AS active,
                       COUNT(*) FILTER (WHERE a.status = 'frozen') AS frozen,
                       COUNT(*) FILTER (WHERE a.status = 'closed') AS closed
                FROM accounts a
                WHERE a.kind <> 'system' AND (:all OR a.owner_id = :uid)
                """)
                .param("all", all).param("uid", user.id())
                .query((rs, n) -> new Totals(rs.getLong("balance"), rs.getLong("active"), rs.getLong("frozen"), rs.getLong("closed")))
                .single();

        // One row per UTC day, zero-filled, so the chart never has gaps. Money in = credits to visible accounts,
        // money out = debits from them (a staff view counts a customer-to-customer transfer on both sides).
        List<Day> daily = jdbc.sql("""
                WITH days AS (
                  SELECT CAST(d AS DATE) AS day
                  FROM generate_series(CAST(:first AS DATE), CAST(:today AS DATE), INTERVAL '1 day') AS d
                ), flows AS (
                  SELECT CAST(j.created_at AT TIME ZONE 'UTC' AS DATE) AS day, e.journal_id, e.amount_cents
                  FROM entries e
                  JOIN journals j ON j.id = e.journal_id
                  JOIN accounts a ON a.id = e.account_id
                  WHERE a.kind <> 'system' AND (:all OR a.owner_id = :uid) AND j.created_at >= :since
                )
                SELECT d.day,
                       COALESCE(SUM(CASE WHEN f.amount_cents > 0 THEN f.amount_cents ELSE 0 END), 0) AS inflow,
                       COALESCE(SUM(CASE WHEN f.amount_cents < 0 THEN -f.amount_cents ELSE 0 END), 0) AS outflow,
                       COUNT(DISTINCT f.journal_id) AS journals
                FROM days d LEFT JOIN flows f ON f.day = d.day
                GROUP BY d.day
                ORDER BY d.day
                """)
                .param("first", first).param("today", today).param("since", since)
                .param("all", all).param("uid", user.id())
                .query((rs, n) -> new Day(rs.getObject("day", LocalDate.class).toString(), rs.getLong("inflow"),
                        rs.getLong("outflow"), rs.getLong("journals")))
                .list();

        List<KindCount> byKind = jdbc.sql("""
                SELECT j.kind, COUNT(*) AS n
                FROM journals j
                WHERE j.created_at >= :since
                  AND EXISTS (SELECT 1 FROM entries e JOIN accounts a ON a.id = e.account_id
                              WHERE e.journal_id = j.id AND a.kind <> 'system' AND (:all OR a.owner_id = :uid))
                GROUP BY j.kind
                ORDER BY n DESC
                """)
                .param("since", since).param("all", all).param("uid", user.id())
                .query((rs, n) -> new KindCount(rs.getString("kind"), rs.getLong("n")))
                .list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("windowDays", WINDOW_DAYS);
        out.put("totals", totals);
        out.put("daily", daily);
        out.put("byKind", byKind);
        return out;
    }
}
