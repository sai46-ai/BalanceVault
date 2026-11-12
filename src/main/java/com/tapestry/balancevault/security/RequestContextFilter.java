package com.tapestry.balancevault.security;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.tapestry.balancevault.support.GlobalErrorHandler;

/**
 * Runs before Spring Security: assigns a request id, writes one access-log line per request (method, path,
 * status, duration, user id; never bodies or query strings), and blocks cross-site state-changing requests
 * using Fetch Metadata / Origin as defence in depth on top of CSRF tokens and SameSite cookies.
 */
// Explicit bean name: the default "requestContextFilter" collides with Spring MVC's own filter bean.
@Component("bvRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger("access");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final Set<String> allowedOrigins;

    public RequestContextFilter() {
        this(parseAllowedOrigins());
    }

    public RequestContextFilter(Set<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins != null ? allowedOrigins : Set.of();
    }

    private static Set<String> parseAllowedOrigins() {
        Set<String> origins = new HashSet<>();
        String allowed = System.getenv("CORS_ALLOWED_ORIGINS");
        if (allowed == null || allowed.isBlank()) {
            allowed = System.getenv("FRONTEND_URL");
        }
        if (allowed != null && !allowed.isBlank()) {
            for (String part : allowed.split(",")) {
                String trimmed = part.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    origins.add(trimmed);
                }
            }
        }
        return Set.copyOf(origins);
    }

    /** Wraps the request with its id and access log; rejects cross-site writes before any work happens. */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        res.setHeader("X-Request-Id", requestId);
        long started = System.nanoTime();
        try {
            if (!SAFE_METHODS.contains(req.getMethod()) && isCrossSite(req)) {
                GlobalErrorHandler.write(res, 403, "Cross-site request blocked", null);
                return;
            }
            if (req.getRequestURI().startsWith("/api/")) {
                res.setHeader("Cache-Control", "no-store");
            }
            chain.doFilter(req, res);
        } finally {
            log.info("action=http {} {} outcome={} ms={}", req.getMethod(), req.getRequestURI(), res.getStatus(),
                    (System.nanoTime() - started) / 1_000_000);
            MDC.clear();
        }
    }

    /** True when the browser says the request came from another site, or the Origin host differs from ours. */
    boolean isCrossSite(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin != null && isAllowedOrigin(origin, req.getServerName())) {
            return false;
        }
        String site = req.getHeader("Sec-Fetch-Site");
        if (site != null) {
            return !site.equals("same-origin") && !site.equals("none");
        }
        if (origin == null) {
            return false;
        }
        try {
            return !req.getServerName().equalsIgnoreCase(URI.create(origin).getHost());
        } catch (IllegalArgumentException malformedOrigin) {
            return true;
        }
    }

    boolean isAllowedOrigin(String origin, String serverName) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        if (allowedOrigins.contains("*")) {
            return true;
        }
        String lowerOrigin = origin.trim().toLowerCase(Locale.ROOT);
        if (allowedOrigins.contains(lowerOrigin)) {
            return true;
        }
        if (lowerOrigin.endsWith("/") && allowedOrigins.contains(lowerOrigin.substring(0, lowerOrigin.length() - 1))) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String originHost = uri.getHost();
            if (originHost != null) {
                if (serverName != null && serverName.equalsIgnoreCase(originHost)) {
                    return true;
                }
                for (String allowed : allowedOrigins) {
                    if (allowed.startsWith("*.") && originHost.endsWith(allowed.substring(1))) {
                        return true;
                    }
                    if (allowed.contains("://")) {
                        try {
                            String allowedHost = URI.create(allowed).getHost();
                            if (originHost.equalsIgnoreCase(allowedHost)) {
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (IllegalArgumentException malformedOrigin) {
            return false;
        }
        return false;
    }
}
