package com.automation.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that all Flyway migrations apply cleanly against an in-memory SQLite database.
 * Catches SQL syntax errors, incompatible column types, and migration ordering issues.
 */
@SpringBootTest
@ActiveProfiles("integration")
class FlywayMigrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void allMigrationsApplyCleanly() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied)
                .as("All Flyway migrations should be applied")
                .hasSizeGreaterThanOrEqualTo(35);
    }

    @Test
    void noPendingMigrations() {
        MigrationInfo[] pending = flyway.info().pending();
        assertThat(pending)
                .as("No pending migrations should remain")
                .isEmpty();
    }

    @Test
    void noFailedMigrations() {
        for (MigrationInfo info : flyway.info().applied()) {
            assertThat(info.getState().isFailed())
                    .as("Migration %s should not be failed", info.getVersion())
                    .isFalse();
        }
    }
}
