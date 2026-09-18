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
        while (SqlStatementCounter.isActive()) {
            SqlStatementCounter.stop();
        }
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

    /**
     * One thread can be measured twice at once — the ratchet measures around a request while the
     * filter measures inside it. The inner measurement must not swallow the outer one; that bug
     * made the ratchet read 0 statements for every route and stay green (2026-09-18).
     */
    @Test
    void nestedMeasurementsBothCount() {
        SqlStatementCounter.start();
        SqlStatementCounter.record();

        SqlStatementCounter.start();
        SqlStatementCounter.record();
        assertThat(SqlStatementCounter.count()).as("inner sees only its own").isEqualTo(1);
        SqlStatementCounter.stop();

        assertThat(SqlStatementCounter.count()).as("outer saw both").isEqualTo(2);
    }

    @Test
    void stopEndsOnlyTheInnermostMeasurement() {
        SqlStatementCounter.start();
        SqlStatementCounter.start();

        SqlStatementCounter.stop();

        assertThat(SqlStatementCounter.isActive()).isTrue();
        SqlStatementCounter.stop();
        assertThat(SqlStatementCounter.isActive()).isFalse();
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
