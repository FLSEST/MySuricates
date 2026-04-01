package com.automation.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionDefaultsTest {

    @Test
    void shellTimeouts_haveCorrectValues() {
        assertThat(ExecutionDefaults.SHELL_TIMEOUT_SECONDS).isEqualTo(300);
        assertThat(ExecutionDefaults.INTERACTION_TIMEOUT_MINUTES).isEqualTo(30);
        assertThat(ExecutionDefaults.SUB_WORKFLOW_MAX_WAIT_MINUTES).isEqualTo(30);
    }

    @Test
    void agenticDefaults_haveCorrectValues() {
        assertThat(ExecutionDefaults.AGENTIC_MAX_ITERATIONS).isEqualTo(10);
        assertThat(ExecutionDefaults.AGENTIC_ITERATION_TIMEOUT_SECONDS).isEqualTo(300);
        assertThat(ExecutionDefaults.AGENTIC_TOTAL_TIMEOUT_SECONDS).isEqualTo(1800);
        assertThat(ExecutionDefaults.AGENTIC_MAX_TOTAL_TOKENS).isEqualTo(200_000);
        assertThat(ExecutionDefaults.AGENTIC_MAX_REPEATED_ACTIONS).isEqualTo(3);
        assertThat(ExecutionDefaults.AGENTIC_ACTION_COMPRESS_THRESHOLD).isEqualTo(100_000);
        assertThat(ExecutionDefaults.AGENTIC_ACTION_COMPRESS_TIMEOUT).isEqualTo(60);
    }

    @Test
    void agenticTotalTimeout_equals30Minutes() {
        assertThat(ExecutionDefaults.AGENTIC_TOTAL_TIMEOUT_SECONDS).isEqualTo(30 * 60);
    }

    @Test
    void pollingIntervals_haveCorrectValues() {
        assertThat(ExecutionDefaults.POLLING_INTERVAL_MS).isEqualTo(2000L);
        assertThat(ExecutionDefaults.HEALTH_CHECK_INTERVAL_MS).isEqualTo(2000L);
    }

    @Test
    void truncationLengths_orderedShortToLong() {
        assertThat(ExecutionDefaults.TRUNCATE_HISTORY).isEqualTo(150);
        assertThat(ExecutionDefaults.TRUNCATE_SHORT).isEqualTo(200);
        assertThat(ExecutionDefaults.TRUNCATE_MEDIUM).isEqualTo(500);
        assertThat(ExecutionDefaults.TRUNCATE_LONG).isEqualTo(2000);
        assertThat(ExecutionDefaults.TRUNCATE_OBSERVATION).isEqualTo(4000);

        // Ordering invariant: history < short < medium < long < observation
        assertThat(ExecutionDefaults.TRUNCATE_HISTORY)
                .isLessThan(ExecutionDefaults.TRUNCATE_SHORT);
        assertThat(ExecutionDefaults.TRUNCATE_SHORT)
                .isLessThan(ExecutionDefaults.TRUNCATE_MEDIUM);
        assertThat(ExecutionDefaults.TRUNCATE_MEDIUM)
                .isLessThan(ExecutionDefaults.TRUNCATE_LONG);
        assertThat(ExecutionDefaults.TRUNCATE_LONG)
                .isLessThan(ExecutionDefaults.TRUNCATE_OBSERVATION);
    }

    @Test
    void networkTimeouts_haveCorrectValues() {
        assertThat(ExecutionDefaults.HTTP_CONNECT_TIMEOUT_MS).isEqualTo(3000);
        assertThat(ExecutionDefaults.HTTP_READ_TIMEOUT_MS).isEqualTo(3000);
        assertThat(ExecutionDefaults.SYNC_CONNECT_TIMEOUT_MS).isEqualTo(10_000);
        assertThat(ExecutionDefaults.SYNC_READ_TIMEOUT_MS).isEqualTo(30_000);
    }

    @Test
    void syncTimeouts_longerThanDefaultTimeouts() {
        assertThat(ExecutionDefaults.SYNC_CONNECT_TIMEOUT_MS)
                .isGreaterThan(ExecutionDefaults.HTTP_CONNECT_TIMEOUT_MS);
        assertThat(ExecutionDefaults.SYNC_READ_TIMEOUT_MS)
                .isGreaterThan(ExecutionDefaults.HTTP_READ_TIMEOUT_MS);
    }

    @Test
    void retentionAndContainer_haveCorrectValues() {
        assertThat(ExecutionDefaults.LOG_RETENTION_DAYS).isEqualTo(30);
        assertThat(ExecutionDefaults.DOCKER_STOP_TIMEOUT_SECONDS).isEqualTo(10);
        assertThat(ExecutionDefaults.INSTANCE_HEALTH_CHECK_TIMEOUT_SECONDS).isEqualTo(60);
    }

    @Test
    void allConstants_arePositive() {
        assertThat(ExecutionDefaults.SHELL_TIMEOUT_SECONDS).isPositive();
        assertThat(ExecutionDefaults.INTERACTION_TIMEOUT_MINUTES).isPositive();
        assertThat(ExecutionDefaults.SUB_WORKFLOW_MAX_WAIT_MINUTES).isPositive();
        assertThat(ExecutionDefaults.AGENTIC_MAX_ITERATIONS).isPositive();
        assertThat(ExecutionDefaults.AGENTIC_ITERATION_TIMEOUT_SECONDS).isPositive();
        assertThat(ExecutionDefaults.AGENTIC_TOTAL_TIMEOUT_SECONDS).isPositive();
        assertThat(ExecutionDefaults.AGENTIC_MAX_TOTAL_TOKENS).isPositive();
        assertThat(ExecutionDefaults.AGENTIC_MAX_REPEATED_ACTIONS).isPositive();
        assertThat(ExecutionDefaults.POLLING_INTERVAL_MS).isPositive();
        assertThat(ExecutionDefaults.HEALTH_CHECK_INTERVAL_MS).isPositive();
        assertThat(ExecutionDefaults.TRUNCATE_LONG).isPositive();
        assertThat(ExecutionDefaults.TRUNCATE_MEDIUM).isPositive();
        assertThat(ExecutionDefaults.TRUNCATE_SHORT).isPositive();
        assertThat(ExecutionDefaults.TRUNCATE_OBSERVATION).isPositive();
        assertThat(ExecutionDefaults.TRUNCATE_HISTORY).isPositive();
        assertThat(ExecutionDefaults.HTTP_CONNECT_TIMEOUT_MS).isPositive();
        assertThat(ExecutionDefaults.HTTP_READ_TIMEOUT_MS).isPositive();
        assertThat(ExecutionDefaults.LOG_RETENTION_DAYS).isPositive();
        assertThat(ExecutionDefaults.DOCKER_STOP_TIMEOUT_SECONDS).isPositive();
    }

    @Test
    void privateConstructor_preventsInstantiation() throws Exception {
        Constructor<ExecutionDefaults> ctor = ExecutionDefaults.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(ctor.getModifiers())).isTrue();
    }
}
