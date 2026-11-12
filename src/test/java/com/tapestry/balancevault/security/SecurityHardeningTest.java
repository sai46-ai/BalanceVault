package com.tapestry.balancevault.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.tapestry.balancevault.support.ApiException;
import com.tapestry.balancevault.support.GlobalErrorHandler;

/**
 * Unit tests verifying Milestone 1 security hardening:
 * BCrypt password hashing, RequestContextFilter origin checking, and CWE-209 stack trace suppression.
 */
class SecurityHardeningTest {

    @Test
    void passwordEncoderHashesAndMatchesCorrectly() {
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        String rawPassword = "T@pestry@Secure2026";
        String encoded = encoder.encode(rawPassword);

        assertNotNull(encoded);
        assertTrue(encoded.startsWith("$2a$"), "BCrypt hash must start with $2a$ prefix");
        assertTrue(encoder.matches(rawPassword, encoded), "Encoder must match raw password with its hash");
        assertFalse(encoder.matches("wrong-password", encoded), "Encoder must reject mismatched password");
    }

    @Test
    void requestContextFilterRecognizesAllowedOrigins() {
        RequestContextFilter filter = new RequestContextFilter(Set.of("https://balancevault-demo.netlify.app", "http://localhost:5173"));

        assertTrue(filter.isAllowedOrigin("https://balancevault-demo.netlify.app", "balancevault-api.onrender.com"));
        assertTrue(filter.isAllowedOrigin("https://balancevault-demo.netlify.app/", "balancevault-api.onrender.com"));
        assertTrue(filter.isAllowedOrigin("http://localhost:5173", "localhost"));
        assertFalse(filter.isAllowedOrigin("https://evil-attacker.com", "balancevault-api.onrender.com"));
    }

    @Test
    void requestContextFilterAllowsSameHostOrigin() {
        RequestContextFilter filter = new RequestContextFilter(Set.of());

        assertTrue(filter.isAllowedOrigin("https://api.balancevault.com", "api.balancevault.com"));
        assertFalse(filter.isAllowedOrigin("https://evil.com", "api.balancevault.com"));
    }

    @Test
    void requestContextFilterWildcardAllowsAllOrigins() {
        RequestContextFilter filter = new RequestContextFilter(Set.of("*"));

        assertTrue(filter.isAllowedOrigin("https://any-site.netlify.app", "api.balancevault.com"));
        assertTrue(filter.isAllowedOrigin("http://localhost:3000", "api.balancevault.com"));
    }

    @Test
    void requestContextFilterCrossSiteBlockedForUntrustedOrigin() {
        RequestContextFilter filter = new RequestContextFilter(Set.of("https://trusted.netlify.app"));

        MockHttpServletRequest trustedReq = new MockHttpServletRequest("POST", "/api/transactions/deposit");
        trustedReq.setServerName("api.balancevault.com");
        trustedReq.addHeader("Origin", "https://trusted.netlify.app");
        trustedReq.addHeader("Sec-Fetch-Site", "cross-site");
        assertFalse(filter.isCrossSite(trustedReq), "Trusted allowed origin must NOT be treated as cross-site");

        MockHttpServletRequest untrustedReq = new MockHttpServletRequest("POST", "/api/transactions/deposit");
        untrustedReq.setServerName("api.balancevault.com");
        untrustedReq.addHeader("Origin", "https://malicious.com");
        untrustedReq.addHeader("Sec-Fetch-Site", "cross-site");
        assertTrue(filter.isCrossSite(untrustedReq), "Untrusted origin must be detected as cross-site");
    }

    @Test
    void globalErrorHandlerSuppressesStackTracesByDefault() {
        GlobalErrorHandler handler = new GlobalErrorHandler();
        ApiException ex = new ApiException(422, "Insufficient funds");

        var response = handler.api(ex);
        Map<String, Object> body = response.getBody();

        assertNotNull(body);
        assertEquals(false, body.get("success"));
        assertEquals("Insufficient funds", body.get("error"));
        assertEquals(422, body.get("code"));
        assertEquals(422, body.get("status"));
        assertNotNull(body.get("at"), "Timestamp 'at' must be present in response");
        assertNull(body.get("stack"), "Stack trace must be suppressed in response payload (CWE-209)");
    }
}
