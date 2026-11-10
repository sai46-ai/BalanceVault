package com.tapestry.balancevault.security;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The session holds only the user id. Each request re-reads that user, so disabling someone or changing their role
 * takes effect on their very next request instead of when their session happens to expire.
 */
public class SessionAuthFilter extends OncePerRequestFilter {
    public static final String SESSION_USER_ID = "bv.uid";

    private final JdbcClient jdbc;

    /** Not a Spring bean on purpose: it is added to the security chain only, so it never runs twice. */
    public SessionAuthFilter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Authenticates the request from the session's user id, or ends a session whose user is no longer active. */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = req.getSession(false);
        if (session != null && session.getAttribute(SESSION_USER_ID) instanceof Long userId) {
            Optional<AppUser> user = jdbc.sql("""
                    SELECT id, username, display_name, role FROM users WHERE id = :id AND status = 'active'
                    """)
                    .param("id", userId)
                    .query((rs, n) -> AppUser.of(rs.getLong("id"), rs.getString("username"),
                            rs.getString("display_name"), rs.getString("role")))
                    .optional();
            if (user.isPresent()) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get(), null,
                        user.get().permissions().stream().map(SimpleGrantedAuthority::new).toList()));
                SecurityContextHolder.setContext(context);
                MDC.put("userId", String.valueOf(userId));
            } else {
                session.invalidate();
            }
        }
        chain.doFilter(req, res);
    }
}
