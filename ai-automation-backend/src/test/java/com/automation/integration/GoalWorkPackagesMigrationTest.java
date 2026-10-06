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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V92 explicitly assigns goals to projects ({@code goal_projects}), turns the previous
 * milestones into work packages ({@code goal_work_packages}: project, position, period) and creates the
 * NEW milestones ({@code goal_milestones}, {@code goal_milestone_work_packages}) – TODO c60bf914
 * (goals stage 1: goal → projects → work packages → TODOs, milestones, outline and timeline).
 *
 * <p>Against its own SQLite file with {@code foreign_keys=true}, as in production: migrate to V91,
 * create existing data, then V92. Two traps that it proves: {@code DROP TABLE goal_milestones} sets
 * all {@code todos.milestone_id} to NULL with foreign keys enabled – the migration must back up
 * and write back the assignments; and the foreign key in {@code todos} names the
 * table by name – it must point to {@code goal_work_packages}, not to the new
 * {@code goal_milestones}.
 */
class GoalWorkPackagesMigrationTest {

    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @TempDir
    Path tmp;

    @Test
    void goalProjectsAreBackfilled_milestonesBecomeWorkPackages_mixedOnesAreSplit_noAssignmentIsLost()
            throws SQLException {
        SQLiteDataSource dataSource = dataSource("migration.db");

        flyway(dataSource, "91").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String p : List.of("pA", "pB", "pC")) {
                st.executeUpdate("INSERT INTO projects (id, name, type, path) VALUES ('" + p + "', '" + p + "', 'CODE', '/tmp/" + p + "')");
            }
            goal(st, "g1");
            goal(st, "g2");
            milestone(st, "m1", "g1", "Werbematerial", "2026-10-10T21:59:59Z", "2026-09-20T10:00:00Z");
            milestone(st, "m2", "g1", "Beta", "2026-10-05T21:59:59Z", null);
            milestone(st, "m4", "g1", "Leer", "2026-10-01T21:59:59Z", null);
            milestone(st, "m3", "g2", "Ohne Projekt", "2026-11-01T21:59:59Z", null);
            // pA: 3 active TODOs, pB: 2 active + 1 archived, pC: only one archived
            todo(st, "a1", "g1", "pA", "m1", "OPEN");
            todo(st, "a2", "g1", "pA", "m1", "DONE");
            todo(st, "a3", "g1", "pA", null, "OPEN");
            todo(st, "b1", "g1", "pB", "m1", "OPEN");
            todo(st, "b2", "g1", "pB", "m1", "ARCHIVED");
            todo(st, "b3", "g1", "pB", "m2", "OPEN");
            todo(st, "c1", "g1", "pC", null, "ARCHIVED");
            todo(st, "n1", "g1", null, "m1", "OPEN");
            todo(st, "x1", null, "pC", null, "OPEN");
        }
        Map<String, String> before = assignments(dataSource, "milestone_id");
        assertThat(before).hasSize(6);

        flyway(dataSource, "92").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(rows(st, "PRAGMA foreign_key_check")).as("kein verwaister Fremdschluessel").isEmpty();
            assertThat(rows(st, "SELECT goal_id, project_id, position FROM goal_projects ORDER BY goal_id, position"))
                    .as("Zielprojekte aus den TODOs, auch archivierten; Position nach aktiven TODOs")
                    .containsExactly("g1|pA|0", "g1|pB|1", "g1|pC|2");

            Map<String, String> packages = new LinkedHashMap<>();
            for (String row : rows(st, "SELECT id, goal_id, project_id, position, title, start_date, end_date, completed_at "
                    + "FROM goal_work_packages ORDER BY goal_id, project_id, position")) {
                packages.put(row.substring(0, row.indexOf('|')), row);
            }
            assertThat(packages).hasSize(5);
            assertThat(packages.get("m4")).as("ohne TODOs: erstes Zielprojekt; altes Datum = Start und Ende")
                    .isEqualTo("m4|g1|pA|0|Leer|2026-10-01T21:59:59Z|2026-10-01T21:59:59Z|null");
            assertThat(packages.get("m1")).as("Mehrheit pA, reached_at wird completed_at")
                    .isEqualTo("m1|g1|pA|1|Werbematerial|2026-10-10T21:59:59Z|2026-10-10T21:59:59Z|2026-09-20T10:00:00Z");
            assertThat(packages.get("m2")).isEqualTo("m2|g1|pB|0|Beta|2026-10-05T21:59:59Z|2026-10-05T21:59:59Z|null");
            assertThat(packages.get("m3")).as("Ziel ohne Projekte: kein Projekt")
                    .isEqualTo("m3|g2|null|0|Ohne Projekt|2026-11-01T21:59:59Z|2026-11-01T21:59:59Z|null");

            List<String> copies = packages.keySet().stream().filter(id -> !id.matches("m\\d")).toList();
            assertThat(copies).as("genau eine Kopie fuer das Minderheitsprojekt pB, auch bei zwei TODOs").hasSize(1);
            String copy = copies.get(0);
            assertThat(copy).matches(UUID_FORMAT);
            assertThat(packages.get(copy))
                    .isEqualTo(copy + "|g1|pB|1|Werbematerial|2026-10-10T21:59:59Z|2026-10-10T21:59:59Z|null");

            Map<String, String> after = assignments(dataSource, "work_package_id");
            assertThat(after).as("keine Zuordnung verloren").hasSameSizeAs(before).containsOnlyKeys(before.keySet());
            assertThat(after).containsEntry("a1", "m1").containsEntry("a2", "m1").containsEntry("n1", "m1")
                    .containsEntry("b3", "m2")
                    .containsEntry("b1", copy).containsEntry("b2", copy);
            assertThat(rows(st, "SELECT COUNT(*) FROM pragma_table_info('todos') WHERE name = 'milestone_id'"))
                    .as("die alte Spalte gibt es nicht mehr").containsExactly("0");
            assertThat(rows(st, "SELECT sql FROM sqlite_master WHERE name = 'idx_todos_work_package'")).singleElement()
                    .asString().contains("work_package_id");
            assertThat(rows(st, "SELECT name FROM sqlite_master WHERE name = 'idx_todos_milestone'")).isEmpty();

            // The foreign key todos.work_package_id points to goal_work_packages – not to the new goal_milestones
            assertThat(rows(st, "SELECT \"table\", \"from\", on_delete FROM pragma_foreign_key_list('todos') "
                    + "WHERE \"from\" = 'work_package_id'")).containsExactly("goal_work_packages|work_package_id|SET NULL");
            st.executeUpdate("INSERT INTO goal_milestones (id, goal_id, title, due_date, created_at, updated_at) "
                    + "VALUES ('ms-new', 'g1', 'Launch', '2026-10-15T21:59:59Z', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE todos SET work_package_id = 'ms-new' WHERE id = 'a3'"))
                    .as("eine Meilenstein-ID ist kein Arbeitspaket").isInstanceOf(SQLException.class).hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE todos SET work_package_id = 'missing' WHERE id = 'a3'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("FOREIGN KEY");
            st.executeUpdate("DELETE FROM goal_work_packages WHERE id = 'm2'");
            assertThat(rows(st, "SELECT work_package_id FROM todos WHERE id = 'b3'")).as("Paket geloescht: TODO bleibt ohne")
                    .containsExactly("null");

            // Zeitraum optional
            st.executeUpdate("INSERT INTO goal_work_packages (id, goal_id, project_id, title, position, created_at, updated_at) "
                    + "VALUES ('wp-open', 'g1', 'pA', 'Irgendwann', 2, '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')");

            // Neue Meilensteine: Verknuepfung mit Paketen, Kaskaden in beide Richtungen
            st.executeUpdate("INSERT INTO goal_milestone_work_packages (milestone_id, work_package_id) VALUES ('ms-new', 'm1')");
            st.executeUpdate("INSERT INTO goal_milestone_work_packages (milestone_id, work_package_id) VALUES ('ms-new', 'wp-open')");
            assertThatThrownBy(() -> st.executeUpdate(
                    "INSERT INTO goal_milestone_work_packages (milestone_id, work_package_id) VALUES ('ms-new', 'missing')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> st.executeUpdate("INSERT INTO goal_milestones (id, goal_id, title, created_at, updated_at) "
                    + "VALUES ('ms-undated', 'g1', 'X', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')"))
                    .as("ein Meilenstein braucht ein Datum").isInstanceOf(SQLException.class).hasMessageContaining("NOT NULL");
            st.executeUpdate("DELETE FROM goal_work_packages WHERE id = 'wp-open'");
            assertThat(rows(st, "SELECT work_package_id FROM goal_milestone_work_packages")).as("Paket weg: Verknuepfung weg")
                    .containsExactly("m1");
            assertThat(rows(st, "PRAGMA foreign_key_check")).isEmpty();

            // Ziel loeschen: Pakete, Meilensteine, Verknuepfungen und Zielprojekte gehen mit, die TODOs bleiben
            st.executeUpdate("DELETE FROM goals WHERE id = 'g1'");
            assertThat(rows(st, "SELECT id FROM goal_work_packages")).containsExactly("m3");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_milestones")).containsExactly("0");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_milestone_work_packages")).containsExactly("0");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_projects")).containsExactly("0");
            assertThat(rows(st, "SELECT goal_id || '/' || IFNULL(work_package_id, '-') FROM todos WHERE id = 'a1'"))
                    .containsExactly("null");
            assertThat(rows(st, "SELECT COUNT(*) FROM todos")).containsExactly("9");

            // Deleting a milestone takes its links with it, the package stays
            goal(st, "g3");
            st.executeUpdate("INSERT INTO goal_milestones (id, goal_id, title, due_date, created_at, updated_at) "
                    + "VALUES ('ms-3', 'g3', 'M', '2026-10-15T21:59:59Z', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')");
            st.executeUpdate("INSERT INTO goal_work_packages (id, goal_id, project_id, title, created_at, updated_at) "
                    + "VALUES ('wp-3', 'g3', 'pC', 'C', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')");
            st.executeUpdate("INSERT INTO goal_milestone_work_packages (milestone_id, work_package_id) VALUES ('ms-3', 'wp-3')");
            st.executeUpdate("DELETE FROM goal_milestones WHERE id = 'ms-3'");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_milestone_work_packages")).containsExactly("0");
            assertThat(rows(st, "SELECT id FROM goal_work_packages WHERE goal_id = 'g3'")).containsExactly("wp-3");

            // Deleting a project: the goal project row goes with it, the package only loses its project
            st.executeUpdate("INSERT INTO goal_projects (goal_id, project_id, position) VALUES ('g3', 'pC', 0)");
            st.executeUpdate("DELETE FROM projects WHERE id = 'pC'");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_projects WHERE goal_id = 'g3'")).containsExactly("0");
            assertThat(rows(st, "SELECT project_id FROM goal_work_packages WHERE id = 'wp-3'")).containsExactly("null");

            assertThat(rows(st, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'goal_work_packages' "
                    + "AND name LIKE 'idx_%' ORDER BY name"))
                    .containsExactly("idx_goal_work_packages_goal", "idx_goal_work_packages_goal_project");
            assertThat(rows(st, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'goal_milestones' "
                    + "AND name LIKE 'idx_%' ORDER BY name")).containsExactly("idx_goal_milestones_goal");
            assertThat(rows(st, "SELECT name FROM sqlite_temp_master WHERE type = 'table'")).as("Temp-Tabellen weg").isEmpty();
            assertThat(rows(st, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    @Test
    void emptyDatabaseMigratesCleanly() throws SQLException {
        SQLiteDataSource dataSource = dataSource("empty.db");

        flyway(dataSource, "92").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String table : List.of("goal_work_packages", "goal_milestones", "goal_milestone_work_packages", "goal_projects")) {
                assertThat(rows(st, "SELECT COUNT(*) FROM " + table)).as(table).containsExactly("0");
            }
            assertThat(rows(st, "SELECT \"table\" FROM pragma_foreign_key_list('todos') WHERE \"from\" = 'work_package_id'"))
                    .containsExactly("goal_work_packages");
        }
    }

    private SQLiteDataSource dataSource(String file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve(file) + "?foreign_keys=true");
        return dataSource;
    }

    private static void goal(Statement st, String id) throws SQLException {
        st.executeUpdate("INSERT INTO goals (id, title, status, created_at, updated_at) "
                + "VALUES ('" + id + "', '" + id + "', 'ACTIVE', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')");
    }

    /** A milestone in the schema up to V91 – after V92 a work package. */
    private static void milestone(Statement st, String id, String goalId, String title, String due, String reachedAt)
            throws SQLException {
        st.executeUpdate("INSERT INTO goal_milestones (id, goal_id, title, due_date, reached_at, created_at, updated_at) "
                + "VALUES ('" + id + "', '" + goalId + "', '" + title + "', '" + due + "', "
                + (reachedAt == null ? "NULL" : "'" + reachedAt + "'")
                + ", '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')");
    }

    private static void todo(Statement st, String id, String goalId, String projectId, String milestoneId, String status)
            throws SQLException {
        st.executeUpdate("INSERT INTO todos (id, title, status, priority, project_id, source, goal_id, milestone_id, "
                + "created_at, updated_at) VALUES ('" + id + "', '" + id + "', '" + status + "', 'MEDIUM', "
                + sql(projectId) + ", 'USER', " + sql(goalId) + ", " + sql(milestoneId)
                + ", '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')");
    }

    private static String sql(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    /** TODO id → assigned package id from the given column (before V92 {@code milestone_id}). */
    private static Map<String, String> assignments(SQLiteDataSource dataSource, String column) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, " + column + " FROM todos WHERE " + column + " IS NOT NULL ORDER BY id")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2));
            }
        }
        return result;
    }

    /** Jede Zeile als {@code a|b|c}; NULL als {@code null}. */
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
                .mixed(true)   // as in application.yml: older migrations mix PRAGMA and DDL
                .target(target)
                .load();
    }
}
