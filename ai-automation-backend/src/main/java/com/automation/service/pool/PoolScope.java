package com.automation.service.pool;

import java.util.Arrays;
import java.util.Locale;

/**
 * Kategorie eines Pool-Eintrags — das Unterscheidungsmerkmal, an dem ein fremder Leser vor dem
 * Installieren erkennt, ob ein Eintrag für sein Projekt gedacht ist.
 *
 * <ul>
 *   <li>{@link #REPO} — greift auf die Struktur dieses Repositories zu
 *       ({@code ai-automation-backend}, {@code mvnw}, {@code scripts/}). Läuft anderswo ins Leere.</li>
 *   <li>{@link #PORTABLE} — über {@code project_path} (bzw. eigene Kontextvariablen) parametrisiert
 *       und damit in jedem Projekt lauffähig.</li>
 * </ul>
 *
 * <p>Der Wert ist ein Pool-Metadatum, kein Feld der Workflow-Definition: er entsteht beim Export
 * ({@code POST /api/v1/pool/export/{workflowId}?scope=repo|portable}) und wird im Pool-Browser
 * angezeigt. Regelwerk: {@code docs/workflow-pool-kuratierung.md}.
 */
public enum PoolScope {

    REPO,
    PORTABLE;

    /** Der Wert, wie er in der Pool-JSON steht. */
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Löst den Query-Parameter auf. Ohne Angabe gilt {@link #PORTABLE}: der Default ist die
     * schwächere Zusage, ein repo-gebundener Eintrag muss bewusst so gekennzeichnet werden.
     *
     * @throws IllegalArgumentException bei einem unbekannten Wert
     */
    public static PoolScope fromParam(String value) {
        if (value == null || value.isBlank()) {
            return PORTABLE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.jsonValue().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unbekannter scope: '" + value + "' — erlaubt sind 'repo' und 'portable'"));
    }
}
