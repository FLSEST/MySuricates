package com.automation.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sqlite-jdbc runs only the FIRST statement of a multi-statement {@code connection-init-sql}
 * (verified against 3.50.3.0 on 2026-09-11: {@code auto_vacuum} silently never applied). Keep the
 * init SQL to a single statement and the other pragmas in the JDBC URL.
 */
class DataSourceConfigGuardTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String resource) throws Exception {
        try (InputStream in = DataSourceConfigGuardTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> datasource(Map<String, Object> root) {
        return (Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("datasource");
    }

    @Test
    void connectionInitSql_isASingleStatement() throws Exception {
        for (String profile : new String[]{"/application.yml", "/application-integration.yml"}) {
            Map<String, Object> ds = datasource(load(profile));
            String initSql = String.valueOf(((Map<String, Object>) ds.get("hikari")).get("connection-init-sql")).trim();

            assertThat(initSql).as(profile).doesNotContain(";");
            assertThat(initSql).as(profile).containsIgnoringCase("auto_vacuum");
        }
    }

    @Test
    void jdbcUrl_carriesForeignKeysAndBusyTimeout() throws Exception {
        for (String profile : new String[]{"/application.yml", "/application-test.yml", "/application-stable.yml", "/application-prod.yml"}) {
            String url = String.valueOf(datasource(load(profile)).get("url"));
            assertThat(url).as(profile).contains("foreign_keys=true").contains("busy_timeout=");
        }
    }
}
