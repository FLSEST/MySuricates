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
 * V95 indexes the derived-query lookups the performance audit reported as unindexed and that run on a
 * hot path or in a growing table (TODO 2d233599). Against a throw-away SQLite file: the indexes exist
 * on the intended columns and SQLite's planner uses them for the lookups.
 */
class FindByIndexGapsMigrationTest {

    private static final Map<String, String> EXPECTED = Map.of(
            "idx_workflows_project_id", "workflows(project_id)",
            "idx_scheduled_workflows_workflow_id", "scheduled_workflows(workflow_id)",
            "idx_scheduled_workflows_chain_id", "scheduled_workflows(chain_id)",
            "idx_webhook_triggers_workflow_id", "webhook_triggers(workflow_id)",
            "idx_todo_triggers_target_workflow_id", "todo_triggers(target_workflow_id)",
            "idx_capabilities_skill_id", "capabilities(skill_id)",
            "idx_dead_letter_entries_workflow_run_id", "dead_letter_entries(workflow_run_id)",
            "idx_workflow_memory_active_created_at", "workflow_memory(active, created_at)");

    @TempDir
    Path tmp;

    @Test
    void indexesExistAndServeTheLookups() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "95").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            Map<String, String> indexes = new HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type = 'index'")) {
                while (rs.next()) indexes.put(rs.getString("name"), rs.getString("sql"));
            }
            EXPECTED.forEach((name, definition) -> assertThat(indexes.get(name)).as(name).contains(definition));
            // The older composite index keeps its name and definition: IF NOT EXISTS must not have hit it.
            assertThat(indexes.get("idx_workflow_memory_active")).contains("workflow_memory(workflow_id, active)");

            assertThat(plan(st, "SELECT id FROM workflows WHERE project_id = 'p'"))
                    .contains("idx_workflows_project_id");
            assertThat(plan(st, "SELECT id FROM scheduled_workflows WHERE workflow_id IN ('a', 'b')"))
                    .contains("idx_scheduled_workflows_workflow_id");
            assertThat(plan(st, "SELECT id FROM scheduled_workflows WHERE chain_id = 'c'"))
                    .contains("idx_scheduled_workflows_chain_id");
            assertThat(plan(st, "SELECT id FROM webhook_triggers WHERE workflow_id = 'w'"))
                    .contains("idx_webhook_triggers_workflow_id");
            assertThat(plan(st, "SELECT id FROM todo_triggers WHERE target_workflow_id = 'w'"))
                    .contains("idx_todo_triggers_target_workflow_id");
            assertThat(plan(st, "SELECT id FROM capabilities WHERE skill_id = 's'"))
                    .contains("idx_capabilities_skill_id");
            assertThat(plan(st, "DELETE FROM dead_letter_entries WHERE workflow_run_id = 'r'"))
                    .contains("idx_dead_letter_entries_workflow_run_id");
            // findTop8ByActiveTrueOrderByCreatedAtDesc: index walk in order, no temp B-tree for the sort
            String memoryPlan = plan(st, "SELECT id FROM workflow_memory WHERE active = 1 ORDER BY created_at DESC LIMIT 8");
            assertThat(memoryPlan).contains("idx_workflow_memory_active_created_at").doesNotContain("TEMP B-TREE");
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
