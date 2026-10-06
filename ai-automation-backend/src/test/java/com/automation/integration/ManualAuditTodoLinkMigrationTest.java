package com.automation.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V98 records whether a managed audit TODO was created by hand (TODO f2ce6c50). The ingest only creates
 * TODOs for HIGH/CRITICAL findings and takes the TODO priority from the severity, so an existing managed
 * link whose TODO is LOW or MEDIUM came from the button. Against a throw-away SQLite file: those become
 * manual, HIGH/CRITICAL ones and unmanaged links stay as they were.
 */
class ManualAuditTodoLinkMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void managedLinksOfLowAndMediumTodosBecomeManual_theRestStaysAutomatic() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db"));

        flyway(dataSource, "97").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String[] todo : new String[][]{{"t-low", "LOW"}, {"t-medium", "MEDIUM"}, {"t-high", "HIGH"},
                    {"t-critical", "CRITICAL"}, {"t-linked", "LOW"}}) {
                st.executeUpdate("INSERT INTO todos (id, title, status, priority, source, created_at, updated_at) "
                        + "VALUES ('" + todo[0] + "', 'T', 'OPEN', '" + todo[1] + "', 'WORKFLOW', "
                        + "'2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z')");
            }
            link(st, "l-low", "t-low", true);
            link(st, "l-medium", "t-medium", true);
            link(st, "l-high", "t-high", true);
            link(st, "l-critical", "t-critical", true);
            link(st, "l-linked", "t-linked", false);
        }

        flyway(dataSource, "98").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(manualOf(st, "l-low")).isTrue();
            assertThat(manualOf(st, "l-medium")).isTrue();
            assertThat(manualOf(st, "l-high")).as("the ingest creates HIGH TODOs itself").isFalse();
            assertThat(manualOf(st, "l-critical")).isFalse();
            assertThat(manualOf(st, "l-linked")).as("an unmanaged link is never reconciled").isFalse();
        }
    }

    private static void link(Statement st, String id, String todoId, boolean managed) throws SQLException {
        st.executeUpdate("INSERT INTO audit_finding_todos (id, finding_id, todo_id, managed) VALUES ('" + id
                + "', 'f-" + id + "', '" + todoId + "', " + (managed ? 1 : 0) + ")");
    }

    private static boolean manualOf(Statement st, String id) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT manual FROM audit_finding_todos WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getBoolean("manual");
        }
    }

    private static Flyway flyway(SQLiteDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .mixed(true)   // as in application.yml: older migrations mix PRAGMA and DDL
                .target(target)
                .load();
    }
}
