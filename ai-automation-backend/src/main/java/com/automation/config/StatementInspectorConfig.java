package com.automation.config;

import com.automation.metrics.CountingStatementInspector;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link CountingStatementInspector} so that the SQL statements of a single call
 * can be counted. Always on: the inspector only increments a ThreadLocal that exists while
 * something measures, so the cost when nobody measures is one lookup per statement.
 */
@Configuration
public class StatementInspectorConfig {

    @Bean
    public HibernatePropertiesCustomizer statementInspectorCustomizer() {
        return properties -> properties.put(
                AvailableSettings.STATEMENT_INSPECTOR, new CountingStatementInspector());
    }
}
