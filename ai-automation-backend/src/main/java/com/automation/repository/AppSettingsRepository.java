package com.automation.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Raw JDBC access to the app_settings table.
 *
 * Uses JdbcTemplate (not raw DataSource.getConnection()) so that queries
 * participate in the current Spring-managed transaction when one exists.
 * With HikariCP pool-size=1 (SQLite), calling dataSource.getConnection()
 * inside a @Transactional method would deadlock — the transaction already
 * holds the only connection, and the raw JDBC call requests a second one
 * that will never become available.
 */
@Repository
@RequiredArgsConstructor
public class AppSettingsRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> get(String key) {
        List<String> results = jdbcTemplate.query(
                "SELECT value FROM app_settings WHERE key = ?",
                (rs, rowNum) -> rs.getString("value"),
                key
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void set(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO app_settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, value
        );
    }
}
