package com.automation.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Macht nicht vertrauenswuerdigen Text – Titel von TODOs, Zielen und Meilensteinen aus Importen,
 * Webhooks oder KI-Ausgaben – zu einer begrenzten Zeile ohne Plattform-Marker und ohne Blockgrenzen.
 * Gebraucht fuer die Kontextvariablen {@code open_todos} (service.workflow), {@code goal_status}
 * (service.goal) und {@code audit_findings} (service.audit); liegt hier, damit alle denselben Sanierer
 * nutzen, ohne voneinander abzuhaengen (TODO d2844a58, TODO 3643beac). Mehrzeiliger Text wie der Report
 * eines frueheren Laufs ({@code previous_report}) geht zeilenweise durch denselben Sanierer, ohne Kuerzen
 * ({@link #defuseLines}, TODO 3bf8c9d8).
 *
 * <p><b>Verkleidete Marker</b> (TODO 3643beac): Ein Modell gibt {@code ＜／audit_findings＞} (Vollbreite) oder
 * ein {@code todo_item:} mit Zero-Width-Space dazwischen gern als ASCII wieder – dann wirkt es. Geprueft wird
 * deshalb auch die kanonische Form (NFKC, ohne Formatzeichen {@code \p{Cf}}); nur wenn sie einen Marker oder
 * eine Blockgrenze zeigt, geht es mit ihr weiter. Normaler Text bleibt, wie er ist – auch Emojis mit
 * Zero-Width-Joiner, die ohne {@code \p{Cf}} zerfielen.
 */
public final class UntrustedText {

    /** Obergrenze je eingefuegtem Titel – lange Titel sind vom Angreifer kontrollierter Prompt-Platz. */
    public static final int MAX_TITLE_CHARS = 200;

    /** Jeder Zeilenumbruch ({@code \v}: auch VT, FF, NEL, U+2028/2029) und Tabs. */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\v\\t]+");
    /** {@code (?U)}: auch Unicode-Leerraum wie NBSP oder U+3000. */
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s{2,}");
    /**
     * Ausgabe-Marker, die die Plattform auswertet (todo_item, gate_*, dashboard_widget), und der Envelope eines
     * Audit-Reports ({@code mysuricates:audit-result}; {@code AuditResultParser} nimmt den letzten, und ein
     * wiedergegebener Titel kann hinter oder statt dem eigenen stehen) – entschaerft durch Entfernen des Doppelpunkts.
     */
    private static final Pattern MARKERS = Pattern.compile("(?iU)(todo[_-]item|gate[_-](?:status|category|description)"
            + "|dashboard[_-]widget|mysuricates(?=[*`\\s]*:[*`\\s]*audit[_-]result))[*`\\s]*:");
    /** Die Blockgrenzen selbst ({@code open_todos}, {@code goal_status}, {@code audit_findings}) duerfen aus einem Titel heraus nicht faelschbar sein. */
    private static final Pattern DELIMITER = Pattern.compile("(?iU)</?\\s*(open_todos|goal_status|audit_findings)\\s*>");
    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");

    private UntrustedText() {
    }

    /** Eine Zeile, gekuerzt auf {@code maxChars}, Marker entschaerft, Blockgrenzen neutralisiert. */
    public static String inline(String raw, int maxChars) {
        if (raw == null) return "";
        String s = defuseLine(collapseSpaces(LINE_BREAKS.matcher(raw).replaceAll(" ")));
        if (s.length() > maxChars) {
            // nie ein Emoji halbieren: ein einzelnes Surrogat waere kein gueltiges Zeichen mehr
            int end = maxChars > 0 && Character.isHighSurrogate(s.charAt(maxChars - 1)) ? maxChars - 1 : maxChars;
            s = s.substring(0, end).trim() + "…";
        }
        return s;
    }

    /**
     * Mehrzeiliger Text, jede Zeile entschaerft wie in {@link #inline} – Marker ohne Doppelpunkt, Blockgrenzen
     * als {@code [name]}, ein verkleideter Marker in der Form, in der er wirken wuerde –, aber ungekuerzt und
     * mit seinen Zeilen. Eine {@code gate_status}-Zeile bleibt so lesbar ({@code gate_status GREEN}); gibt ein
     * Modell sie wieder, wertet sie kein Parser als dessen eigenes Urteil (TODO 3bf8c9d8).
     */
    public static String defuseLines(String raw) {
        if (raw == null) return "";
        StringJoiner lines = new StringJoiner("\n");
        for (String line : raw.split("\n", -1)) {   // -1: auch leere Zeilen am Ende bleiben
            lines.add(defuseLine(line));
        }
        return lines.toString();
    }

    /** Die Entschaerfung selbst, ohne Kuerzen und ohne Umbrueche anzufassen. */
    private static String defuseLine(String line) {
        String s = line;
        String canonical = canonical(s);
        if (!canonical.equals(s) && (MARKERS.matcher(canonical).find() || DELIMITER.matcher(canonical).find())) {
            s = canonical;   // ein verkleideter Marker: weiter mit der Form, in der er wirken wuerde
        }
        s = defuseMarkers(s);
        return DELIMITER.matcher(s).replaceAll(m -> "[" + m.group(1).toLowerCase(Locale.ROOT) + "]");
    }

    /** Die Form, in der ein Modell den Text wiedergeben koennte: NFKC, ohne unsichtbare Formatzeichen. */
    private static String canonical(String s) {
        return collapseSpaces(FORMAT_CHARS.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC)).replaceAll(""));
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
