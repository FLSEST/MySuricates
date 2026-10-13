package com.automation.service.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DockerCliProbeTest {

    /** Probe with a movable clock that counts how often it would start the CLI. */
    private static final class CountingProbe extends DockerCliProbe {
        final AtomicInteger asks = new AtomicInteger();
        boolean answer = true;

        CountingProbe(MovableClock clock) {
            super(clock);
        }

        @Override
        boolean ask() {
            asks.incrementAndGet();
            return answer;
        }
    }

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("the answer is kept for the TTL, then the CLI is asked again")
    void cachesTheAnswer() {
        MovableClock clock = new MovableClock();
        CountingProbe probe = new CountingProbe(clock);

        assertThat(probe.reachable()).isTrue();
        probe.answer = false;
        clock.now = clock.now.plus(DockerCliProbe.TTL).minusSeconds(1);
        assertThat(probe.reachable()).as("still the cached answer").isTrue();
        assertThat(probe.asks).hasValue(1);

        clock.now = clock.now.plusSeconds(2);
        assertThat(probe.reachable()).isFalse();
        assertThat(probe.asks).hasValue(2);
    }
}
