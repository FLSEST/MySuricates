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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V87 indexes the schedule-run and TODO-by-run lookups that scanned whole tables (audit finding
 * d4b62bf3). Against a throw-away SQLite file: the indexes exist on the intended columns and
 * SQLite's planner uses them for the lookups.
 */
class ScheduleAndTodoRunIndexMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void indexesExistAndServeTheLookups() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "87").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            Map<String, String> indexes = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name IN ("
                    + "'idx_workflow_runs_schedule_started', 'idx_chain_runs_schedule', "
                    + "'idx_todos_last_fix_run_id', 'idx_todos_workflow_run_id')")) {
                while (rs.next()) indexes.put(rs.getString("name"), rs.getString("sql"));
            }
            assertThat(indexes).hasSize(4);
            assertThat(indexes.get("idx_workflow_runs_schedule_started"))
                    .contains("workflow_runs(triggered_by_schedule_id, started_at)");
            assertThat(indexes.get("idx_chain_runs_schedule")).contains("chain_runs(triggered_by_schedule_id)");
            assertThat(indexes.get("idx_todos_last_fix_run_id")).contains("todos(last_fix_run_id)");
            assertThat(indexes.get("idx_todos_workflow_run_id")).contains("todos(workflow_run_id)");

            assertThat(plan(st, "SELECT id FROM workflow_runs WHERE triggered_by_schedule_id = 's' ORDER BY started_at DESC"))
                    .contains("idx_workflow_runs_schedule_started");
            assertThat(plan(st, "SELECT id FROM chain_runs WHERE triggered_by_schedule_id = 's'"))
                    .contains("idx_chain_runs_schedule");
            assertThat(plan(st, "SELECT id FROM todos WHERE last_fix_run_id = 'r'"))
                    .contains("idx_todos_last_fix_run_id");
            assertThat(plan(st, "SELECT id FROM todos WHERE workflow_run_id = 'r'"))
                    .contains("idx_todos_workflow_run_id");
        }
    }

    private static String plan(Statement st, String query) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + query)) {
            while (rs.next()) plan.append(rs.getString("detail")).append('\n');
        }
        return plan.toString();
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
