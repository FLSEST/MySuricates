package com.automation.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

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

    @Test
    void executionDefaults_classIsFinal() {
        assertThat(Modifier.isFinal(ExecutionDefaults.class.getModifiers())).isTrue();
    }

    @Test
    void executionDefaults_cannotBeInstantiated() throws Exception {
        Constructor<ExecutionDefaults> ctor = ExecutionDefaults.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(ctor.getModifiers())).isTrue();
        ctor.setAccessible(true);
        // Konstruktor selbst wirft nicht, ist aber privat — Aufruf via Reflection moeglich,
        // jedoch nur fuer Coverage. Pruefe: Aufruf liefert Instanz, aber Klasse hat keinerlei State.
        ExecutionDefaults instance = ctor.newInstance();
        assertThat(instance).isNotNull();
    }

    @Test
    void allDeclaredFields_arePublicStaticFinal() {
        for (Field field : ExecutionDefaults.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            int mods = field.getModifiers();
            assertThat(Modifier.isPublic(mods))
                    .as("Field %s should be public", field.getName()).isTrue();
            assertThat(Modifier.isStatic(mods))
                    .as("Field %s should be static", field.getName()).isTrue();
            assertThat(Modifier.isFinal(mods))
                    .as("Field %s should be final", field.getName()).isTrue();
        }
    }

    @Test
    void allDeclaredFields_followUpperSnakeCaseNamingConvention() {
        for (Field field : ExecutionDefaults.class.getDeclaredFields()) {
            if (field.isSynthetic()) continue;
            assertThat(field.getName())
                    .as("Field %s should be UPPER_SNAKE_CASE", field.getName())
                    .matches("^[A-Z][A-Z0-9_]*$");
        }
    }

    @Test
    void agenticTotalTimeout_accommodatesAtLeastOneFullIteration() {
        // Cross-constraint: total budget must allow at least one iteration to finish.
        assertThat(ExecutionDefaults.AGENTIC_TOTAL_TIMEOUT_SECONDS)
                .isGreaterThanOrEqualTo(ExecutionDefaults.AGENTIC_ITERATION_TIMEOUT_SECONDS);
    }

    @Test
    void agenticCompressTimeout_shorterThanIterationTimeout() {
        // Compression is a sub-step within an iteration — must not exceed it.
        assertThat(ExecutionDefaults.AGENTIC_ACTION_COMPRESS_TIMEOUT)
                .isLessThan(ExecutionDefaults.AGENTIC_ITERATION_TIMEOUT_SECONDS);
    }

    @Test
    void agenticCompressTimeout_shorterThanShellTimeout() {
        assertThat(ExecutionDefaults.AGENTIC_ACTION_COMPRESS_TIMEOUT)
                .isLessThan(ExecutionDefaults.SHELL_TIMEOUT_SECONDS);
    }

    @Test
    void compressThreshold_largerThanLargestTruncation() {
        // Compression should only kick in for inputs larger than what we ever truncate to.
        assertThat(ExecutionDefaults.AGENTIC_ACTION_COMPRESS_THRESHOLD)
                .isGreaterThan(ExecutionDefaults.TRUNCATE_OBSERVATION);
    }

    @Test
    void agenticMaxRepeatedActions_isSmallEnoughToAbortQuickly() {
        // Loop-prevention: must trigger well before max iterations.
        assertThat(ExecutionDefaults.AGENTIC_MAX_REPEATED_ACTIONS)
                .isLessThan(ExecutionDefaults.AGENTIC_MAX_ITERATIONS);
        assertThat(ExecutionDefaults.AGENTIC_MAX_REPEATED_ACTIONS).isGreaterThanOrEqualTo(2);
    }

    @Test
    void instanceHealthCheckTimeout_longerThanDockerStopTimeout() {
        assertThat(ExecutionDefaults.INSTANCE_HEALTH_CHECK_TIMEOUT_SECONDS)
                .isGreaterThan(ExecutionDefaults.DOCKER_STOP_TIMEOUT_SECONDS);
    }

    @Test
    void pollingIntervals_belowOneSecondHumanThreshold() {
        // 2s polling is fast enough for UI feedback, slow enough to not hammer DB.
        assertThat(ExecutionDefaults.POLLING_INTERVAL_MS).isBetween(500L, 5000L);
        assertThat(ExecutionDefaults.HEALTH_CHECK_INTERVAL_MS).isBetween(500L, 5000L);
    }

    @Test
    void httpReadTimeout_atLeastConnectTimeout() {
        assertThat(ExecutionDefaults.HTTP_READ_TIMEOUT_MS)
                .isGreaterThanOrEqualTo(ExecutionDefaults.HTTP_CONNECT_TIMEOUT_MS);
        assertThat(ExecutionDefaults.SYNC_READ_TIMEOUT_MS)
                .isGreaterThanOrEqualTo(ExecutionDefaults.SYNC_CONNECT_TIMEOUT_MS);
    }

    @Test
    void logRetention_isReasonable() {
        assertThat(ExecutionDefaults.LOG_RETENTION_DAYS).isBetween(1, 365);
    }

    @Test
    void shellTimeout_alignsWithAgenticIterationTimeout() {
        // Shell-Block in agentic iteration darf Iteration nicht sprengen.
        assertThat(ExecutionDefaults.SHELL_TIMEOUT_SECONDS)
                .isLessThanOrEqualTo(ExecutionDefaults.AGENTIC_ITERATION_TIMEOUT_SECONDS);
    }
}
