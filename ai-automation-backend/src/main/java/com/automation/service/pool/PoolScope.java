package com.automation.service.pool;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Kategorie eines Pool-Eintrags — das Unterscheidungsmerkmal, an dem ein fremder Leser vor dem
 * Installieren erkennt, ob ein Eintrag für sein Projekt gedacht ist.
 *
 * <ul>
 *   <li>{@link #REPO} — greift auf die Struktur dieses Repositories zu
 *       ({@code ai-automation-backend}, {@code mvnw}, {@code scripts/}). Läuft anderswo ins Leere.</li>
 *   <li>{@link #PORTABLE} — über {@code project_path} (bzw. eigene Kontextvariablen) parametrisiert
 *       und damit in jedem Projekt lauffähig.</li>
 *   <li>{@link #EXTERNAL} — braucht ein Werkzeug außerhalb dieses Repositories und des gewählten
 *       Projekts; die Voraussetzung steht in der Beschreibung (TODO 7ddacd1d, etwa die SaaS-Factory).</li>
 * </ul>
 *
 * <p>Der Wert ist ein Pool-Metadatum, kein Feld der Workflow-Definition: er entsteht beim Export
 * ({@code POST /api/v1/pool/export/{workflowId}?scope=repo|portable|external}) und wird im Pool-Browser
 * angezeigt. Regelwerk: {@code docs/workflow-pool-kuratierung.md}.
 */
public enum PoolScope {

    REPO,
    PORTABLE,
    EXTERNAL;

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
                        "Unbekannter scope: '" + value + "' — erlaubt sind " + allowedValues()));
    }

    /** Die erlaubten Werte für Fehlermeldungen, etwa {@code 'repo', 'portable', 'external'}. */
    public static String allowedValues() {
        return Arrays.stream(values()).map(s -> "'" + s.jsonValue() + "'").collect(Collectors.joining(", "));
    }
}
