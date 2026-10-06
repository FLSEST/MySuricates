package com.automation.entity;

import java.time.Duration;
import java.time.Instant;

/**
 * State of an appointment in the goal - a work package (basis: its end) or a milestone
 * (basis: its date), TODO 259f4f66 / c60bf914. Never stored, always computed on read.
 */
public enum GoalDueState {
    OPEN, DUE_SOON, OVERDUE, REACHED;

    /** From here an open appointment is called "due soon". */
    public static final Duration DUE_SOON_WINDOW = Duration.ofDays(3);

    /**
     * Reached beats any date; without a date it stays OPEN - never "due soon" or
     * "overdue".
     */
    public static GoalDueState of(Instant due, boolean reached, Instant now) {
        if (reached) {
            return REACHED;
        }
        if (due == null) {
            return OPEN;
        }
        if (due.isBefore(now)) {
            return OVERDUE;
        }
        return due.isAfter(now.plus(DUE_SOON_WINDOW)) ? OPEN : DUE_SOON;
    }
}
