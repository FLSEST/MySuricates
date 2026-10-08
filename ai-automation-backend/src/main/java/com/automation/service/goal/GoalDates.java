package com.automation.service.goal;

import com.automation.util.IsoDates;

import java.time.Instant;

/**
 * Dates of work packages and milestones (TODO 259f4f66, c60bf914): an ISO timestamp
 * as sent; a plain date ({@code 2026-10-15}) means, for the start, "from the beginning of that day"
 * (00:00:00 UTC), for the end and the due date "until the end of that day" (23:59:59 UTC) –
 * it only becomes overdue the day after. Empty = no date. The parsing itself lives in {@link IsoDates}, which
 * the due date of TODOs uses as well (TODO 6968dc90).
 */
final class GoalDates {

    private GoalDates() {
    }

    static Instant start(String value) {
        return IsoDates.startOfDay(value);
    }

    static Instant end(String value) {
        return IsoDates.endOfDay(value);
    }

    /** Title of a package or milestone: trimmed, not empty, at most {@code max} characters. */
    static String title(String raw, int max, String what) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException(what + " braucht einen Titel");
        }
        if (title.length() > max) {
            throw new IllegalArgumentException("Der Titel ist laenger als " + max + " Zeichen");
        }
        return title;
    }
}
