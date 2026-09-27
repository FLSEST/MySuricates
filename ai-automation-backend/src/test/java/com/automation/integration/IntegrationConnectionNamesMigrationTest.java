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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V86 rebuilds {@code integration_connections} (SQLite cannot drop the old UNIQUE on
 * {@code provider_id}). Runs against its own throw-away SQLite file: migrate to V85, write a
 * pre-existing row, then apply V86 and check that the row survived as connection {@code default}.
 */
class IntegrationConnectionNamesMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void existingRowsBecomeTheDefaultConnectionAndNamesAreUniquePerProvider() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "85").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO integration_connections (id, provider_id, status, access_token_encrypted, "
                    + "account_info, client_id_encrypted, client_secret_encrypted) "
                    + "VALUES ('c1', 'github', 'CONNECTED', 'tok', '{\"name\":\"alex\"}', 'cid', 'csec')");
        }

        flyway(dataSource, "86").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, provider_id, connection_name, status, access_token_encrypted, "
                    + "account_info, client_id_encrypted, client_secret_encrypted FROM integration_connections")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("id")).isEqualTo("c1");
                assertThat(rs.getString("provider_id")).isEqualTo("github");
                assertThat(rs.getString("connection_name")).isEqualTo("default");
                assertThat(rs.getString("status")).isEqualTo("CONNECTED");
                assertThat(rs.getString("access_token_encrypted")).isEqualTo("tok");
                assertThat(rs.getString("account_info")).isEqualTo("{\"name\":\"alex\"}");
                assertThat(rs.getString("client_id_encrypted")).isEqualTo("cid");
                assertThat(rs.getString("client_secret_encrypted")).isEqualTo("csec");
                assertThat(rs.next()).isFalse();
            }

            // a second, named connection of the same provider is now allowed …
            st.execute("INSERT INTO integration_connections (id, provider_id, connection_name) VALUES ('c2', 'github', 'work')");
            // … but a name stays unique within its provider
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO integration_connections (id, provider_id, connection_name) VALUES ('c3', 'github', 'work')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("UNIQUE");
            // the column default keeps writers that do not know the name working
            st.execute("INSERT INTO integration_connections (id, provider_id) VALUES ('c4', 'jira')");
            try (ResultSet rs = st.executeQuery("SELECT connection_name FROM integration_connections WHERE id = 'c4'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("default");
            }
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
