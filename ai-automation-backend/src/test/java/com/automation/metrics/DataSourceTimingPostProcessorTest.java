package com.automation.metrics;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DataSourceTimingPostProcessorTest {

    private final DataSourceTimingPostProcessor processor = new DataSourceTimingPostProcessor();

    @Test
    void wrapsADataSourceAndKeepsThePoolReachable() throws Exception {
        DataSource pool = mock(DataSource.class);

        Object result = processor.postProcessAfterInitialization(pool, "dataSource");

        assertThat(result).isInstanceOf(TimingDataSource.class);
        assertThat(((TimingDataSource) result).getTargetDataSource()).isSameAs(pool);
    }

    @Test
    void doesNotWrapTwice() {
        TimingDataSource alreadyWrapped = new TimingDataSource(mock(DataSource.class));

        assertThat(processor.postProcessAfterInitialization(alreadyWrapped, "dataSource")).isSameAs(alreadyWrapped);
    }

    @Test
    void leavesOtherBeansAlone() {
        Object bean = new Object();

        assertThat(processor.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    }
}
