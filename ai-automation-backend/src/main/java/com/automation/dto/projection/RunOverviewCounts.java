package com.automation.dto.projection;

/**
 * The run counters at the top of the dashboard from ONE query (SUM/CASE) instead of five separate
 * {@code count} calls (TODO 2ccb9e40). {@code succeeded} counts COMPLETED and
 * COMPLETED_WITH_ERRORS, {@code startedSince} the runs from the given point in time (start of day).
 */
public record RunOverviewCounts(long total, long succeeded, long failed, long startedSince) {}
