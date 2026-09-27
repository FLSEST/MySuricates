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
 * V92 ordnet Ziele ausdruecklich Projekten zu ({@code goal_projects}) und baut {@code goal_milestones}
 * neu auf (Projekt, Position, Datum optional) – TODO c60bf914 (Ziele Stufe 1: Ziel → Projekte →
 * Teilziele → TODOs, Gliederungsansicht und Zeitstrahl mit Projektbaendern).
 *
 * <p>Gegen eine eigene SQLite-Datei mit {@code foreign_keys=true}, wie im Betrieb: bis V91 migrieren,
 * Bestand anlegen, dann V92. Die Falle, die das beweist: {@code DROP TABLE goal_milestones} setzt
 * mit eingeschalteten Fremdschluesseln alle {@code todos.milestone_id} auf NULL – die Migration muss
 * die Zuordnungen sichern und zurueckschreiben.
 */
class GoalSubgoalsMigrationTest {

    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    @TempDir
    Path tmp;

    @Test
    void goalProjectsAreBackfilled_milestonesGetProjectAndPosition_mixedOnesAreSplit_noAssignmentIsLost()
            throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

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
            // pA: 3 aktive TODOs, pB: 2 aktive + 1 archiviertes, pC: nur ein archiviertes
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
        Map<String, String> before = milestoneOfTodo(dataSource);
        assertThat(before).hasSize(6);

        flyway(dataSource, "92").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(rows(st, "SELECT goal_id, project_id, position FROM goal_projects ORDER BY goal_id, position"))
                    .as("Zielprojekte aus den TODOs, auch archivierten; Position nach aktiven TODOs")
                    .containsExactly("g1|pA|0", "g1|pB|1", "g1|pC|2");

            Map<String, String> milestones = new LinkedHashMap<>();
            for (String row : rows(st, "SELECT id, goal_id, project_id, position, title, reached_at FROM goal_milestones "
                    + "ORDER BY goal_id, project_id, position")) {
                milestones.put(row.substring(0, row.indexOf('|')), row);
            }
            assertThat(milestones).hasSize(5);
            assertThat(milestones.get("m4")).as("ohne TODOs: erstes Zielprojekt").isEqualTo("m4|g1|pA|0|Leer|null");
            assertThat(milestones.get("m1")).as("Mehrheit pA, reached_at bleibt")
                    .isEqualTo("m1|g1|pA|1|Werbematerial|2026-09-20T10:00:00Z");
            assertThat(milestones.get("m2")).isEqualTo("m2|g1|pB|0|Beta|null");
            assertThat(milestones.get("m3")).as("Ziel ohne Projekte: kein Projekt").isEqualTo("m3|g2|null|0|Ohne Projekt|null");

            List<String> copies = milestones.keySet().stream().filter(id -> !id.matches("m\\d")).toList();
            assertThat(copies).as("genau eine Kopie fuer das Minderheitsprojekt pB, auch bei zwei TODOs").hasSize(1);
            String copy = copies.get(0);
            assertThat(copy).matches(UUID_FORMAT);
            assertThat(milestones.get(copy)).isEqualTo(copy + "|g1|pB|1|Werbematerial|null");
            assertThat(rows(st, "SELECT due_date FROM goal_milestones WHERE id = '" + copy + "'"))
                    .containsExactly("2026-10-10T21:59:59Z");

            Map<String, String> after = milestoneOfTodo(dataSource);
            assertThat(after).as("kein milestone_id verloren").hasSameSizeAs(before).containsOnlyKeys(before.keySet());
            assertThat(after).containsEntry("a1", "m1").containsEntry("a2", "m1").containsEntry("n1", "m1")
                    .containsEntry("b3", "m2")
                    .containsEntry("b1", copy).containsEntry("b2", copy);

            // Datum ist jetzt optional
            st.executeUpdate("INSERT INTO goal_milestones (id, goal_id, project_id, title, due_date, position, created_at, updated_at) "
                    + "VALUES ('m5', 'g1', 'pA', 'Irgendwann', NULL, 2, '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')");

            // Der Fremdschluessel todos.milestone_id zeigt auf die neue Tabelle
            assertThatThrownBy(() -> st.executeUpdate("UPDATE todos SET milestone_id = 'missing' WHERE id = 'a3'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("FOREIGN KEY");
            st.executeUpdate("DELETE FROM goal_milestones WHERE id = 'm2'");
            assertThat(rows(st, "SELECT milestone_id FROM todos WHERE id = 'b3'")).containsExactly("null");

            // Ziel loeschen: Teilziele und Zielprojekte gehen mit, die TODOs bleiben
            st.executeUpdate("DELETE FROM goals WHERE id = 'g1'");
            assertThat(rows(st, "SELECT id FROM goal_milestones")).containsExactly("m3");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_projects")).containsExactly("0");
            assertThat(rows(st, "SELECT goal_id || '/' || IFNULL(milestone_id, '-') FROM todos WHERE id = 'a1'"))
                    .containsExactly("null");
            assertThat(rows(st, "SELECT COUNT(*) FROM todos")).containsExactly("9");

            // Projekt loeschen: Zielprojekt-Zeile geht mit, das Teilziel verliert nur sein Projekt
            goal(st, "g3");
            st.executeUpdate("INSERT INTO goal_projects (goal_id, project_id, position) VALUES ('g3', 'pC', 0)");
            st.executeUpdate("INSERT INTO goal_milestones (id, goal_id, project_id, title, created_at, updated_at) "
                    + "VALUES ('m6', 'g3', 'pC', 'C', '2026-09-27T00:00:00Z', '2026-09-27T00:00:00Z')");
            st.executeUpdate("DELETE FROM projects WHERE id = 'pC'");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_projects WHERE goal_id = 'g3'")).containsExactly("0");
            assertThat(rows(st, "SELECT project_id FROM goal_milestones WHERE id = 'm6'")).containsExactly("null");

            assertThat(rows(st, "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'goal_milestones' "
                    + "AND name LIKE 'idx_%' ORDER BY name"))
                    .containsExactly("idx_goal_milestones_goal", "idx_goal_milestones_goal_project");
            assertThat(rows(st, "SELECT name FROM sqlite_temp_master WHERE type = 'table'")).as("Temp-Tabellen weg").isEmpty();
        }
    }

    @Test
    void emptyDatabaseMigratesCleanly() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("empty.db") + "?foreign_keys=true");

        flyway(dataSource, "92").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_milestones")).containsExactly("0");
            assertThat(rows(st, "SELECT COUNT(*) FROM goal_projects")).containsExactly("0");
        }
    }

    private static void goal(Statement st, String id) throws SQLException {
        st.executeUpdate("INSERT INTO goals (id, title, status, created_at, updated_at) "
                + "VALUES ('" + id + "', '" + id + "', 'ACTIVE', '2026-09-01T00:00:00Z', '2026-09-01T00:00:00Z')");
    }

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

    private static Map<String, String> milestoneOfTodo(SQLiteDataSource dataSource) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, milestone_id FROM todos WHERE milestone_id IS NOT NULL ORDER BY id")) {
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
                .mixed(true)   // wie in application.yml: aeltere Migrationen mischen PRAGMA und DDL
                .target(target)
                .load();
    }
}
