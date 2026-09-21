package com.automation.executor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepIndexCounterTest {

    private final StepIndexCounter counter = new StepIndexCounter();

    @Test
    void next_countsUpFromZero() {
        assertThat(counter.next()).isZero();
        assertThat(counter.next()).isEqualTo(1);
        assertThat(counter.next()).isEqualTo(2);
    }

    @Test
    void preAllocatedIndex_isReturnedByTheNextCallOnlyOnce() {
        counter.next();
        int reserved = counter.preAllocate();

        assertThat(reserved).isEqualTo(1);
        assertThat(counter.next()).isEqualTo(1);
        assertThat(counter.next()).isEqualTo(2);
    }

    @Test
    void preAllocatingTwice_keepsOnlyTheLatestReservation() {
        counter.preAllocate();
        int second = counter.preAllocate();

        assertThat(counter.next()).isEqualTo(second).isEqualTo(1);
        assertThat(counter.next()).isEqualTo(2);
    }

    @Test
    void startAt_movesTheCounterForResume_butIgnoresZeroAndNegative() {
        counter.startAt(0);
        counter.startAt(-3);
        assertThat(counter.next()).isZero();

        counter.startAt(10);
        assertThat(counter.next()).isEqualTo(10);
        assertThat(counter.preAllocate()).isEqualTo(11);
    }
}
