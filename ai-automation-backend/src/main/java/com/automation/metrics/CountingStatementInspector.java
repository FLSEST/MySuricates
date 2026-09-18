package com.automation.metrics;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.io.Serial;

/**
 * Hibernate hook that feeds {@link SqlStatementCounter}. Returns every statement unchanged —
 * it only counts.
 *
 * <p>Registered through {@code StatementInspectorConfig}. Hibernate calls this for every
 * statement it prepares, so the work per call has to stay at one ThreadLocal lookup.
 */
public class CountingStatementInspector implements StatementInspector {

    @Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String inspect(String sql) {
        SqlStatementCounter.record();
        return sql;
    }
}
