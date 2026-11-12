package com.tapestry.balancevault;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import com.tapestry.balancevault.audit.AuditService;

/** BalanceVault entry point. Flyway migrates the schema before the admin seed below runs. */
@SpringBootApplication
public class BalanceVaultApplication {

    /** Starts the app; config comes from environment variables and the project-root .env. */
    public static void main(String[] args) {
        SpringApplication.run(BalanceVaultApplication.class, args);
    }

    /**
     * Creates the first admin from ADMIN_USERNAME / ADMIN_PASSWORD when that username does not exist yet.
     * An existing admin is never overwritten, so a password changed later is not reset on restart.
     */
    @Bean
    ApplicationRunner seedAdmin(JdbcClient jdbc, AuditService audit, TransactionTemplate tx,
                                PasswordEncoder passwordEncoder,
                                @Value("${bv.admin-username}") String username,
                                @Value("${bv.admin-password}") String password) {
        return args -> {
            if (username.isBlank() || password.isBlank()) {
                throw new IllegalStateException("ADMIN_USERNAME and ADMIN_PASSWORD must be set in .env");
            }
            String normalized = username.trim().toLowerCase();
            tx.executeWithoutResult(status -> {
                boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE username = :u)")
                        .param("u", normalized).query(Boolean.class).single();
                if (exists) {
                    return;
                }
                long id = jdbc.sql("""
                        INSERT INTO users (username, display_name, password, role) VALUES (:u, 'Administrator', :p, 'admin')
                        RETURNING id
                        """)
                        .param("u", normalized).param("p", passwordEncoder.encode(password)).query(Long.class).single();
                audit.record(id, "user.seed_admin", "user", String.valueOf(id), "success", Map.of("role", "admin"));
            });
        };
    }
}
