package com.automation.service.goal;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Datumsangaben von Arbeitspaketen und Meilensteinen (TODO 259f4f66, c60bf914): ein ISO-Zeitpunkt
 * wie gesendet; ein reines Datum ({@code 2026-10-15}) heisst beim Beginn „ab Anfang dieses Tages“
 * (00:00:00 UTC), beim Ende und bei der Faelligkeit „bis Ende dieses Tages“ (23:59:59 UTC) –
 * ueberfaellig wird es erst am Tag danach. Leer = kein Datum.
 */
final class GoalDates {

    private GoalDates() {
    }

    static Instant start(String value) {
        return parse(value, LocalTime.MIDNIGHT);
    }

    static Instant end(String value) {
        return parse(value, LocalTime.of(23, 59, 59));
    }

    private static Instant parse(String value, LocalTime timeOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return trimmed.length() == 10
                    ? LocalDate.parse(trimmed).atTime(timeOfDay).toInstant(ZoneOffset.UTC)
                    : Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new LocalizedArgumentException(ErrorMessage.INVALID_DATE, Map.of("value", trimmed));
        }
    }

    /** Titel eines Pakets oder Meilensteins: getrimmt, nicht leer, hoechstens {@code max} Zeichen. */
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
