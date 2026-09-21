package com.automation.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountingStatementInspectorTest {

    private final CountingStatementInspector inspector = new CountingStatementInspector();

    @AfterEach
    void tearDown() {
        while (SqlStatementCounter.isActive()) {
            SqlStatementCounter.stop();
        }
    }

    @Test
    void returnsTheStatementUnchangedAndCountsIt() {
        SqlStatementCounter.start();

        String sql = "select * from todos where id=?";
        assertThat(inspector.inspect(sql)).isSameAs(sql);
        inspector.inspect("update todos set status=?");

        assertThat(SqlStatementCounter.count()).isEqualTo(2);
    }

    @Test
    void passesThroughWithoutAnActiveMeasurement() {
        assertThat(inspector.inspect("select 1")).isEqualTo("select 1");
        assertThat(inspector.inspect(null)).isNull();
        assertThat(SqlStatementCounter.isActive()).isFalse();
    }
}
