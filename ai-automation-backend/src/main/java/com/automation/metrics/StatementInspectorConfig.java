package com.automation.metrics;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link CountingStatementInspector} so that the SQL statements of a single call
 * can be counted. Always on: the inspector only increments a ThreadLocal that exists while
 * something measures, so the cost when nobody measures is one lookup per statement.
 *
 * <p>Lives in {@code metrics} rather than {@code config} so that the dependency between the two
 * packages stays one-way — {@code metrics} reads {@code RequestMetricsConfig}, and nothing in
 * {@code config} reaches back ({@code PackageCycleRatchetTest}).
 */
@Configuration
public class StatementInspectorConfig {

    @Bean
    public HibernatePropertiesCustomizer statementInspectorCustomizer() {
        return properties -> properties.put(
                AvailableSettings.STATEMENT_INSPECTOR, new CountingStatementInspector());
    }
}
