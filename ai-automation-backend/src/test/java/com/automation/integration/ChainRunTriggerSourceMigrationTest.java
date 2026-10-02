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
 * V96 gives a chain run its starter (TODO 6db47bc2). Against a throw-away SQLite file: an old chain run
 * with a schedule id becomes {@code SCHEDULE} — the only start that is certain afterwards —, every other
 * old chain run stays empty (manual and wish starts are not distinguishable, nothing invented).
 */
class ChainRunTriggerSourceMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void scheduledOldChainRunsBecomeSchedule_andTheRestStaysEmpty() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "95").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO workflow_chains (id, name, created_at, updated_at) "
                    + "VALUES ('c-1', 'C', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')");
            st.executeUpdate("INSERT INTO chain_runs (id, chain_id, status, triggered_by_schedule_id) "
                    + "VALUES ('cr-scheduled', 'c-1', 'COMPLETED', 'sched-1')");
            st.executeUpdate("INSERT INTO chain_runs (id, chain_id, status) VALUES ('cr-other', 'c-1', 'COMPLETED')");
        }

        flyway(dataSource, "96").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(triggerSourceOf(st, "cr-scheduled")).isEqualTo("SCHEDULE");
            assertThat(triggerSourceOf(st, "cr-other")).as("nothing invented for old runs").isNull();
        }
    }

    private static String triggerSourceOf(Statement st, String id) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT trigger_source FROM chain_runs WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString("trigger_source");
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
