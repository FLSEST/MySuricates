package com.automation.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionWaitCounterTest {

    @AfterEach
    void tearDown() {
        ConnectionWaitCounter.stop();
    }

    @Test
    void recordWithoutMeasurementIsANoOp() {
        ConnectionWaitCounter.record(1_000);

        assertThat(ConnectionWaitCounter.isActive()).isFalse();
        assertThat(ConnectionWaitCounter.waitedNanos()).isZero();
    }

    @Test
    void accumulatesEveryAcquisitionOfTheMeasurement() {
        ConnectionWaitCounter.start();
        ConnectionWaitCounter.record(1_000);
        ConnectionWaitCounter.record(2_500);

        assertThat(ConnectionWaitCounter.isActive()).isTrue();
        assertThat(ConnectionWaitCounter.waitedNanos()).isEqualTo(3_500);
    }

    /** Unlike the SQL counter this one does not nest: a second start discards the first. */
    @Test
    void startDiscardsAPreviousMeasurement() {
        ConnectionWaitCounter.start();
        ConnectionWaitCounter.record(1_000);

        ConnectionWaitCounter.start();

        assertThat(ConnectionWaitCounter.waitedNanos()).isZero();
    }

    @Test
    void stopEndsTheMeasurementAndIsSafeWithoutStart() {
        ConnectionWaitCounter.stop();

        ConnectionWaitCounter.start();
        ConnectionWaitCounter.record(500);
        ConnectionWaitCounter.stop();

        assertThat(ConnectionWaitCounter.isActive()).isFalse();
        assertThat(ConnectionWaitCounter.waitedNanos()).isZero();
    }

    @Test
    void measurementIsPerThread() throws Exception {
        ConnectionWaitCounter.start();
        ConnectionWaitCounter.record(700);

        AtomicLong seenByOtherThread = new AtomicLong(-1);
        Thread other = new Thread(() -> {
            ConnectionWaitCounter.record(9_999);
            seenByOtherThread.set(ConnectionWaitCounter.waitedNanos());
        });
        other.start();
        other.join();

        assertThat(seenByOtherThread.get()).isZero();
        assertThat(ConnectionWaitCounter.waitedNanos()).isEqualTo(700);
    }
}
