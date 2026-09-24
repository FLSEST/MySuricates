package com.automation.dto.response;

import java.time.Instant;

/**
 * The scheduler's two pauses: the maintenance switch ({@code enabled}: set in the UI, no end, due
 * schedules fire together once it is off) and the skip-until window from the configuration
 * ({@code pausedUntil}: occurrences before it are skipped, null when none is active — TODO a776fd2a).
 */
public record SchedulerPauseResponse(boolean enabled, Instant pausedUntil) {}
