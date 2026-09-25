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
 * V89 gives a workflow run its chain run (TODO a3b89355). Against a throw-away SQLite file: runs
 * from before the migration keep an empty column (no backfill), the index sits on the new column
 * and SQLite's planner uses it for the runs of one chain run.
 */
class WorkflowRunChainRunIdMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void chainRunIdColumnAndIndexExist_andOldRunsStayEmpty() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "88").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO projects (id, name, type, path) VALUES ('p-1', 'P', 'CODE', '/tmp/p-1')");
            st.executeUpdate("INSERT INTO workflows (id, project_id, name) VALUES ('wf-1', 'p-1', 'W')");
            st.executeUpdate("INSERT INTO workflow_runs (id, workflow_id, status, trigger_source) "
                    + "VALUES ('run-old', 'wf-1', 'COMPLETED', 'CHAIN')");
        }

        flyway(dataSource, "89").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT chain_run_id FROM workflow_runs WHERE id = 'run-old'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("chain_run_id")).as("no backfill: nothing invented for old runs").isNull();
            }
            try (ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index' "
                    + "AND name = 'idx_workflow_runs_chain_run_id'")) {
                assertThat(rs.next()).as("index idx_workflow_runs_chain_run_id").isTrue();
                assertThat(rs.getString("sql")).contains("workflow_runs(chain_run_id)");
            }
            assertThat(plan(st, "SELECT id FROM workflow_runs WHERE chain_run_id = 'cr'"))
                    .contains("idx_workflow_runs_chain_run_id");
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
