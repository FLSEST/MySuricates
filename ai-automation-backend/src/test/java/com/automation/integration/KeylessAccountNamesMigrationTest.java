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
 * V90 (TODO 6e1e87c2): RSS/Atom and Open-Meteo stored a fixed German account name
 * ("… (ohne Zugangsdaten)") that the integrations page showed in every language. Existing
 * connections get the language-neutral name; other providers and edited names stay untouched.
 */
class KeylessAccountNamesMigrationTest {

    @TempDir
    Path tmp;

    @Test
    void germanKeylessAccountNamesBecomeNeutral_othersStay() throws SQLException {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tmp.resolve("migration.db") + "?foreign_keys=true");

        flyway(dataSource, "89").migrate();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            insert(st, "c-1", "feed", "default", "{\"name\":\"RSS/Atom (ohne Zugangsdaten)\"}");
            insert(st, "c-2", "feed", "nodejs", "{\"name\":\"RSS/Atom (ohne Zugangsdaten)\"}");
            insert(st, "c-3", "open-meteo", "default", "{\"name\":\"Open-Meteo (ohne Zugangsdaten)\"}");
            insert(st, "c-4", "ical-feed", "default", "{\"name\":\"iCal-Feed\"}");
            insert(st, "c-5", "github", "default", "{\"name\":\"octocat (ohne Zugangsdaten)\"}");
        }

        flyway(dataSource, "90").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(accountInfo(st, "c-1")).isEqualTo("{\"name\":\"RSS/Atom\"}");
            assertThat(accountInfo(st, "c-2")).isEqualTo("{\"name\":\"RSS/Atom\"}");
            assertThat(accountInfo(st, "c-3")).isEqualTo("{\"name\":\"Open-Meteo\"}");
            assertThat(accountInfo(st, "c-4")).isEqualTo("{\"name\":\"iCal-Feed\"}");
            assertThat(accountInfo(st, "c-5")).as("only the two keyless providers").isEqualTo("{\"name\":\"octocat (ohne Zugangsdaten)\"}");
        }
    }

    private static void insert(Statement st, String id, String provider, String name, String info) throws SQLException {
        st.executeUpdate("INSERT INTO integration_connections (id, provider_id, connection_name, status, account_info) "
                + "VALUES ('" + id + "', '" + provider + "', '" + name + "', 'CONNECTED', '" + info + "')");
    }

    private static String accountInfo(Statement st, String id) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT account_info FROM integration_connections WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
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
