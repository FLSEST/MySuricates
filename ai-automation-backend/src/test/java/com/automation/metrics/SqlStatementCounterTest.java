package com.automation.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementCounterTest {

    @AfterEach
    void tearDown() {
        SqlStatementCounter.stop();
    }

    @Test
    void countsStatementsWhileActive() {
        SqlStatementCounter.start();

        SqlStatementCounter.record();
        SqlStatementCounter.record();

        assertThat(SqlStatementCounter.count()).isEqualTo(2);
    }

    @Test
    void ignoresStatementsWhenNotStarted() {
        SqlStatementCounter.record();

        assertThat(SqlStatementCounter.count()).isZero();
    }

    /**
     * The inspector runs on every statement in production, so recording without an active
     * measurement must not allocate a counter that then never gets cleaned up.
     */
    @Test
    void recordWithoutStartLeavesNoThreadLocalBehind() {
        SqlStatementCounter.record();

        assertThat(SqlStatementCounter.isActive()).isFalse();
    }

    @Test
    void startResetsAPreviousMeasurement() {
        SqlStatementCounter.start();
        SqlStatementCounter.record();

        SqlStatementCounter.start();
        SqlStatementCounter.record();

        assertThat(SqlStatementCounter.count()).isEqualTo(1);
    }

    @Test
    void stopEndsTheMeasurement() {
        SqlStatementCounter.start();
        SqlStatementCounter.record();

        SqlStatementCounter.stop();

        assertThat(SqlStatementCounter.isActive()).isFalse();
        assertThat(SqlStatementCounter.count()).isZero();
    }

    /**
     * Requests run on virtual threads ({@code spring.threads.virtual.enabled: true}), so two
     * concurrent measurements must not see each other's statements.
     */
    @Test
    void countsPerThread() throws InterruptedException {
        SqlStatementCounter.start();
        SqlStatementCounter.record();

        AtomicInteger otherThreadCount = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                SqlStatementCounter.start();
                SqlStatementCounter.record();
                SqlStatementCounter.record();
                otherThreadCount.set(SqlStatementCounter.count());
                SqlStatementCounter.stop();
            } finally {
                done.countDown();
            }
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(otherThreadCount).hasValue(2);
        assertThat(SqlStatementCounter.count()).isEqualTo(1);
    }
}
