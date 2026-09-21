package com.automation.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimingDataSourceTest {

    private final DataSource pool = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final TimingDataSource dataSource = new TimingDataSource(pool);

    @AfterEach
    void tearDown() {
        ConnectionWaitCounter.stop();
    }

    @Test
    void handsOutThePoolsOwnConnectionWithoutMeasurement() throws Exception {
        when(pool.getConnection()).thenReturn(connection);

        assertThat(dataSource.getConnection()).isSameAs(connection);
        assertThat(ConnectionWaitCounter.isActive()).isFalse();
    }

    @Test
    void recordsTheAcquisitionTimeWhileMeasuring() throws Exception {
        when(pool.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(20);
            return connection;
        });
        ConnectionWaitCounter.start();

        assertThat(dataSource.getConnection()).isSameAs(connection);

        assertThat(ConnectionWaitCounter.waitedNanos()).isGreaterThanOrEqualTo(15_000_000L);
    }

    @Test
    void recordsWithCredentialsToo() throws Exception {
        when(pool.getConnection("user", "secret")).thenAnswer(invocation -> {
            Thread.sleep(20);
            return connection;
        });
        ConnectionWaitCounter.start();

        assertThat(dataSource.getConnection("user", "secret")).isSameAs(connection);
        assertThat(ConnectionWaitCounter.waitedNanos()).isGreaterThanOrEqualTo(15_000_000L);
    }

    @Test
    void credentialsVariantPassesThroughWithoutMeasurement() throws Exception {
        when(pool.getConnection("user", "secret")).thenReturn(connection);

        assertThat(dataSource.getConnection("user", "secret")).isSameAs(connection);
        assertThat(ConnectionWaitCounter.waitedNanos()).isZero();
    }

    /** A failed acquisition — a pool timeout — is waiting too and must not vanish from the numbers. */
    @Test
    void failedAcquisitionIsStillCounted() throws Exception {
        when(pool.getConnection()).thenAnswer(invocation -> {
            Thread.sleep(20);
            throw new SQLException("Connection is not available, request timed out");
        });
        ConnectionWaitCounter.start();

        assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
        assertThat(ConnectionWaitCounter.waitedNanos()).isGreaterThanOrEqualTo(15_000_000L);
    }

    /** ConnectionPoolWatchdogService reaches Hikari through unwrap — the wrapper must not hide it. */
    @Test
    void unwrapReachesTheWrappedPool() throws Exception {
        when(pool.isWrapperFor(java.io.Closeable.class)).thenReturn(true);

        assertThat(dataSource.isWrapperFor(java.io.Closeable.class)).isTrue();
        assertThat(dataSource.isWrapperFor(TimingDataSource.class)).isTrue();
        assertThat(dataSource.unwrap(TimingDataSource.class)).isSameAs(dataSource);
        assertThat(dataSource.getTargetDataSource()).isSameAs(pool);
    }
}
