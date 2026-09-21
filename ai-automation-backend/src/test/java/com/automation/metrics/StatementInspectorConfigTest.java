package com.automation.metrics;

import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatementInspectorConfigTest {

    @Test
    void customizerRegistersTheCountingInspector() {
        Map<String, Object> hibernateProperties = new HashMap<>();
        hibernateProperties.put("hibernate.format_sql", "false");

        new StatementInspectorConfig().statementInspectorCustomizer().customize(hibernateProperties);

        assertThat(hibernateProperties.get(AvailableSettings.STATEMENT_INSPECTOR))
                .isInstanceOf(CountingStatementInspector.class);
        assertThat(hibernateProperties).containsEntry("hibernate.format_sql", "false");
    }
}
