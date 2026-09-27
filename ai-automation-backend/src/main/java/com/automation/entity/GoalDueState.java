package com.automation.entity;

import java.time.Duration;
import java.time.Instant;

/**
 * Zustand eines Termins im Ziel – eines Arbeitspakets (Basis: sein Ende) oder eines Meilensteins
 * (Basis: sein Datum), TODO 259f4f66 / c60bf914. Nie gespeichert, immer beim Lesen berechnet.
 */
public enum GoalDueState {
    OPEN, DUE_SOON, OVERDUE, REACHED;

    /** Ab hier heisst ein offener Termin „bald faellig“. */
    public static final Duration DUE_SOON_WINDOW = Duration.ofDays(3);

    /**
     * Erreicht schlaegt jedes Datum; ohne Datum bleibt es bei OPEN – nie „bald faellig“ oder
     * „ueberfaellig“.
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
