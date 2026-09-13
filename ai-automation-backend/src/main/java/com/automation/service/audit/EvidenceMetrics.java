package com.automation.service.audit;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministischer Mini-Parser fuer messbare Werte im Evidence-Text eines Audit-Findings.
 * Erkennt Zahlen mit einer bekannten Einheit ("340 LOC", "12 deps", "3,5 MB", "80 %") und liefert
 * pro kanonischer Einheit den groessten gefundenen Wert. Grundlage der Verschlechterungsregel fuer
 * akzeptierte Risiken ({@link AuditAcceptancePolicy}); alles Nicht-Erkannte wird ignoriert.
 */
public final class EvidenceMetrics {
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("loc", "LOC"), Map.entry("lines", "LOC"), Map.entry("zeilen", "LOC"),
            Map.entry("deps", "deps"), Map.entry("dependencies", "deps"), Map.entry("abhängigkeiten", "deps"),
            Map.entry("kb", "KB"), Map.entry("mb", "MB"), Map.entry("gb", "GB"), Map.entry("ms", "ms"),
            Map.entry("%", "%"),
            Map.entry("files", "files"), Map.entry("dateien", "files"),
            Map.entry("classes", "classes"), Map.entry("klassen", "classes"),
            Map.entry("methods", "methods"), Map.entry("methoden", "methods"),
            Map.entry("tests", "tests"),
            Map.entry("warnings", "warnings"), Map.entry("warnungen", "warnings"),
            Map.entry("errors", "errors"), Map.entry("fehler", "errors"),
            Map.entry("issues", "issues"),
            Map.entry("vulnerabilities", "vulnerabilities"), Map.entry("schwachstellen", "vulnerabilities"));

    private static final Pattern METRIC = Pattern.compile(
            "(?<![\\w.,])(\\d+(?:[.,]\\d+)?)\\s*(%|(?:" + String.join("|", CANONICAL.keySet().stream()
                    .filter(k -> !k.equals("%")).map(Pattern::quote).toList()) + ")\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private EvidenceMetrics() {}

    /** @return kanonische Einheit → groesster Wert; leer, wenn der Text keine messbaren Werte enthaelt. */
    public static Map<String, Double> parse(String evidence) {
        Map<String, Double> metrics = new TreeMap<>();
        if (evidence == null || evidence.isBlank()) return metrics;
        Matcher matcher = METRIC.matcher(evidence);
        while (matcher.find()) {
            String key = CANONICAL.get(matcher.group(2).toLowerCase(Locale.ROOT));
            double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
            metrics.merge(key, value, Math::max);
        }
        return metrics;
    }

    /** Formatiert einen Wert samt Einheit fuer Notizen, z. B. "320 LOC" oder "3.5 MB". */
    public static String format(double value, String unit) {
        String number = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        return number + (unit.equals("%") ? " %" : " " + unit);
    }
}
