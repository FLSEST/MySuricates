package com.automation.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Macht nicht vertrauenswuerdigen Text – Titel von TODOs, Zielen und Meilensteinen aus Importen,
 * Webhooks oder KI-Ausgaben – zu einer begrenzten Zeile ohne Plattform-Marker und ohne Blockgrenzen.
 * Gebraucht fuer die Kontextvariablen {@code open_todos} (service.workflow), {@code goal_status}
 * (service.goal) und {@code audit_findings} (service.audit); liegt hier, damit alle denselben Sanierer
 * nutzen, ohne voneinander abzuhaengen (TODO d2844a58, TODO 3643beac).
 */
public final class UntrustedText {

    /** Obergrenze je eingefuegtem Titel – lange Titel sind vom Angreifer kontrollierter Prompt-Platz. */
    public static final int MAX_TITLE_CHARS = 200;

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n\\t\\u2028\\u2029]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s{2,}");
    /** Ausgabe-Marker, die die Plattform auswertet (todo_item, gate_*, dashboard_widget) – entschaerft durch Entfernen des Doppelpunkts. */
    private static final Pattern MARKERS = Pattern.compile(
            "(?i)(todo[_-]item|gate[_-](?:status|category|description)|dashboard[_-]widget)[*`\\s]*:");
    /** Die Blockgrenzen selbst ({@code open_todos}, {@code goal_status}, {@code audit_findings}) duerfen aus einem Titel heraus nicht faelschbar sein. */
    private static final Pattern DELIMITER = Pattern.compile("(?i)</?\\s*(open_todos|goal_status|audit_findings)\\s*>");

    private UntrustedText() {
    }

    /** Eine Zeile, gekuerzt auf {@code maxChars}, Marker entschaerft, Blockgrenzen neutralisiert. */
    public static String inline(String raw, int maxChars) {
        if (raw == null) return "";
        String s = LINE_BREAKS.matcher(raw).replaceAll(" ");
        s = collapseSpaces(s);
        s = defuseMarkers(s);
        s = DELIMITER.matcher(s).replaceAll(m -> "[" + m.group(1).toLowerCase(Locale.ROOT) + "]");
        if (s.length() > maxChars) {
            s = s.substring(0, maxChars).trim() + "…";
        }
        return s;
    }

    /** Entfernt den Doppelpunkt hinter Plattform-Markern, damit ein Titel keinen Befund und kein Gate faelscht. */
    public static String defuseMarkers(String s) {
        return s == null ? "" : MARKERS.matcher(s).replaceAll("$1");
    }

    /** Fasst Leerraum-Folgen zu einem Leerzeichen zusammen und schneidet die Raender ab. */
    public static String collapseSpaces(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").trim();
    }
}
