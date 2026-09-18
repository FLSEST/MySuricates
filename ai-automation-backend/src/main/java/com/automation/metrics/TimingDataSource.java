package com.automation.metrics;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps the real DataSource to time {@code getConnection()} into {@link ConnectionWaitCounter}.
 *
 * <p>Extends Spring's {@link DelegatingDataSource} so that {@code unwrap}/{@code isWrapperFor}
 * keep working — {@code ConnectionPoolWatchdogService} reaches the {@code HikariDataSource}
 * through exactly those, and would lose its pool handle if this wrapper hid it.
 *
 * <p>Only the acquisition is timed, not the work done on the connection: the wrapper hands out
 * the pool's own Connection unchanged.
 */
public class TimingDataSource extends DelegatingDataSource {

    public TimingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!ConnectionWaitCounter.isActive()) {
            return super.getConnection();
        }
        long start = System.nanoTime();
        try {
            return super.getConnection();
        } finally {
            ConnectionWaitCounter.record(System.nanoTime() - start);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        if (!ConnectionWaitCounter.isActive()) {
            return super.getConnection(username, password);
        }
        long start = System.nanoTime();
        try {
            return super.getConnection(username, password);
        } finally {
            ConnectionWaitCounter.record(System.nanoTime() - start);
        }
    }
}
