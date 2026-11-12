package com.tapestry.balancevault.support;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns every failure into {success:false, error, stack, requestId} and logs it. Request bodies are never logged:
 * they can hold passwords and invitation tokens.
 */
@RestControllerAdvice
public class GlobalErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Expected business failures (validation, permission, insufficient funds ...). */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException ex) {
        log.warn("outcome=rejected status={} error={}", ex.status(), ex.getMessage());
        return ResponseEntity.status(ex.status()).body(body(ex.status(), ex.getMessage(), ex));
    }

    /** Bean Validation failures: report each field and its rule, never the rejected value. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex) {
        String message = "Invalid input: " + ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("outcome=rejected status=400 error={}", message);
        return ResponseEntity.badRequest().body(body(400, message, ex));
    }

    /** Malformed JSON, wrong types or unknown fields. The parser message may quote the body, so it is not logged. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        log.warn("outcome=rejected status=400 error=unreadable request body");
        return ResponseEntity.badRequest().body(body(400, "Invalid input: " + ex.getMostSpecificCause().getMessage(), ex));
    }

    /** Bad query/path parameter types or missing parameters. */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> badParam(Exception ex) {
        log.warn("outcome=rejected status=400 error={}", ex.getMessage());
        return ResponseEntity.badRequest().body(body(400, "Invalid input: " + ex.getMessage(), ex));
    }

    /** Wrong Content-Type on a JSON endpoint. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        log.warn("outcome=rejected status=415 error={}", ex.getMessage());
        return ResponseEntity.status(415).body(body(415, "Send JSON with Content-Type: application/json", ex));
    }

    /** Unknown route or verb. */
    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception ex) {
        int status = ex instanceof HttpRequestMethodNotSupportedException ? 405 : 404;
        log.warn("outcome=rejected status={} error={}", status, ex.getMessage());
        return ResponseEntity.status(status).body(body(status, ex.getMessage(), ex));
    }

    /** Spring Security denials raised inside a controller. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> denied(AccessDeniedException ex) {
        log.warn("outcome=denied status=403 error={}", ex.getMessage());
        return ResponseEntity.status(403).body(body(403, ex.getMessage(), ex));
    }

    /** Anything unexpected: full stack to the log and sanitized response to the caller. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("outcome=error status=500", ex);
        return ResponseEntity.internalServerError().body(body(500, ex.getMessage(), ex));
    }

    /** Writes the same error shape from servlet filters and Spring Security handlers (outside Spring MVC). */
    public static void write(HttpServletResponse res, int status, String message, Throwable ex) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(JSON.writeValueAsString(body(status, message, ex)));
    }

    /** The response body shared by every error. Suppresses stack traces unless DEBUG_STACKTRACE=true (CWE-209). */
    private static Map<String, Object> body(int status, String message, Throwable ex) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", message == null ? (ex != null ? ex.getClass().getSimpleName() : "Error") : message);
        out.put("code", status);
        out.put("status", status);
        out.put("at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        out.put("requestId", MDC.get("requestId"));
        if ("true".equalsIgnoreCase(System.getenv("DEBUG_STACKTRACE")) && ex != null) {
            StringWriter trace = new StringWriter();
            ex.printStackTrace(new PrintWriter(trace));
            out.put("stack", trace.toString());
        }
        return out;
    }

    private static Map<String, Object> body(String message, Throwable ex) {
        return body(500, message, ex);
    }
}
