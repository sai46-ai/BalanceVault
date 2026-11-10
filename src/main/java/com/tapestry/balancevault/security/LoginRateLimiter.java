package com.tapestry.balancevault.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.tapestry.balancevault.support.ApiException;

/**
 * Per-IP fixed-window limit for login and onboarding. Keyed on the client IP only, never on a username, so an
 * attacker cannot lock out other users' allowance. Per-account lockout lives in AuthController.
 * ponytail: in-memory and per instance; move to a shared store (e.g. a DB table) if the app runs more than one replica.
 */
@Component
public class LoginRateLimiter {
    private static final int LIMIT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED_IPS = 10_000;

    private record Window(Instant start, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Counts one attempt for this IP and throws 429 once the window's limit is exceeded. */
    public void check(String ip) {
        Instant now = Instant.now();
        Window window = windows.compute(ip, (key, current) ->
                current == null || current.start().plus(WINDOW).isBefore(now)
                        ? new Window(now, 1)
                        : new Window(current.start(), current.count() + 1));
        if (windows.size() > MAX_TRACKED_IPS) {
            windows.entrySet().removeIf(e -> e.getValue().start().plus(WINDOW).isBefore(now));
        }
        if (window.count() > LIMIT) {
            throw new ApiException(429, "Too many attempts from this address. Try again in 15 minutes.");
        }
    }
}
