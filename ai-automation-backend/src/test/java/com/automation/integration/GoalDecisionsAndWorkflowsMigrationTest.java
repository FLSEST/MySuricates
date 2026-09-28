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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V93 haelt Entscheidungen am TODO fest (TODO 68a5e24e Ziele Stufe 2: „Entschieden“ als sichtbarer
 * Zustand am TODO), V94 haengt Workflows an Ziele und Arbeitspakete (TODO 52bc1e8c Ziele Stufe 3:
 * Workflows am Ziel oder Teilziel anzeigen, starten und als Stand nutzen). Gegen eine eigene
 * SQLite-Datei mit {@code foreign_keys=true}: bis V92 migrieren, Bestand anlegen, dann V94.
 */
class GoalDecisionsAndWorkflowsMigrationTest {

    private static final String NOW = "'2026-09-28T00:00:00Z'";

    @TempDir
    Path tmp;

    @Test
    void decisionsAreOptionalColumns_andGoalWorkflowsAreUniquePerTarget_withCascades() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "92").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO projects (id, name, type, path) VALUES ('p1', 'P', 'CODE', '/tmp/p1')");
            st.executeUpdate("INSERT INTO goals (id, title, status, created_at, updated_at) VALUES ('g1', 'Release', 'ACTIVE', " + NOW + ", " + NOW + ")");
            st.executeUpdate("INSERT INTO todos (id, title, status, priority, source, goal_id, open_question, created_at, updated_at) "
                    + "VALUES ('t1', 'Alt', 'OPEN', 'MEDIUM', 'USER', 'g1', 'Welcher Stil?', " + NOW + ", " + NOW + ")");
        }

        flyway(dataSource, "94").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(rows(st, "SELECT open_question, decision, decided_at, decided_by FROM todos WHERE id = 't1'"))
                    .as("Bestand bleibt, ohne Entscheidung").containsExactly("Welcher Stil?|null|null|null");
            st.executeUpdate("UPDATE todos SET decision = 'Handgezeichnet', decided_at = " + NOW + ", decided_by = 'AI', "
                    + "open_question = NULL WHERE id = 't1'");
            assertThat(rows(st, "SELECT decision, decided_by FROM todos WHERE id = 't1'")).containsExactly("Handgezeichnet|AI");

            st.executeUpdate("INSERT INTO workflows (id, project_id, name) VALUES ('wf1', 'p1', 'Bauen')");
            st.executeUpdate("INSERT INTO workflows (id, project_id, name) VALUES ('wf2', 'p1', 'Stand')");
            st.executeUpdate("INSERT INTO goal_work_packages (id, goal_id, project_id, title, created_at, updated_at) "
                    + "VALUES ('wp1', 'g1', 'p1', 'Doku', " + NOW + ", " + NOW + ")");
            attach(st, "a1", null, "wf1", null);
            assertThat(rows(st, "SELECT role FROM goal_workflows WHERE id = 'a1'")).as("Standardrolle").containsExactly("WORKER");
            attach(st, "a2", "wp1", "wf1", "'STATUS'");
            attach(st, "a3", null, "wf2", "'STATUS'");
            assertThatThrownBy(() -> attach(st, "dup-goal", null, "wf1", null))
                    .as("dieselbe Zuordnung am Ziel – NULL zaehlt als eigener Wert").isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> attach(st, "dup-wp", "wp1", "wf1", null)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> attach(st, "bad-wp", "missing", "wf1", null)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO goal_workflows (id, goal_id, created_at, updated_at) "
                    + "VALUES ('no-wf', 'g1', " + NOW + ", " + NOW + ")")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("NOT NULL");

            st.executeUpdate("DELETE FROM goal_work_packages WHERE id = 'wp1'");
            assertThat(rows(st, "SELECT id FROM goal_workflows ORDER BY id")).as("Paket weg: seine Zuordnung weg")
                    .containsExactly("a1", "a3");
            st.executeUpdate("DELETE FROM workflows WHERE id = 'wf2'");
            assertThat(rows(st, "SELECT id FROM goal_workflows")).as("Workflow hart geloescht: Zuordnung weg").containsExactly("a1");
            st.executeUpdate("DELETE FROM goals WHERE id = 'g1'");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_workflows")).containsExactly("0");
            assertThat(rows(st, "SELECT COUNT(*) FROM workflows WHERE id = 'wf1'")).as("der Workflow bleibt").containsExactly("1");
            assertThat(rows(st, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    private static void attach(Statement st, String id, String workPackageId, String workflowId, String role) throws SQLException {
        st.executeUpdate("INSERT INTO goal_workflows (id, goal_id, work_package_id, workflow_id" + (role != null ? ", role" : "")
                + ", created_at, updated_at) VALUES ('" + id + "', 'g1', " + (workPackageId == null ? "NULL" : "'" + workPackageId + "'")
                + ", '" + workflowId + "'" + (role != null ? ", " + role : "") + ", " + NOW + ", " + NOW + ")");
    }

    private static List<String> rows(Statement st, String query) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(query)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) row.append('|');
                    row.append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }

    private static Flyway flyway(SQLiteDataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .mixed(true)   // wie in application.yml: aeltere Migrationen mischen PRAGMA und DDL
                .target(target)
                .load();
    }
}
