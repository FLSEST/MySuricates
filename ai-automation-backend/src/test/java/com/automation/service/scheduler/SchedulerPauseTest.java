package com.automation.service.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins {@link SchedulerPause#active()}: either pause holds the scheduler, both at once too (Audit-TODO 8a5ce408). */
class SchedulerPauseTest {

    private static final Optional<Instant> WINDOW = Optional.of(Instant.parse("2026-10-01T00:00:00Z"));

    @Test
    void neitherPause_isNotActive() {
        assertThat(new SchedulerPause(false, Optional.empty()).active()).isFalse();
    }

    @Test
    void maintenanceAlone_isActive() {
        assertThat(new SchedulerPause(true, Optional.empty()).active()).isTrue();
    }

    @Test
    void skipUntilAlone_isActive() {
        assertThat(new SchedulerPause(false, WINDOW).active()).isTrue();
    }

    @Test
    void bothPauses_areActive() {
        assertThat(new SchedulerPause(true, WINDOW).active()).isTrue();
    }
}
