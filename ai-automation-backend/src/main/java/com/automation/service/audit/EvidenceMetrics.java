package com.automation.service.audit;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
  * Deterministic mini parser for measurable values in the evidence text of an audit finding.
 * <ul>
  *   <li>{@link #parse}: numbers with a known unit ("340 LOC", "531 NCLOC", "12 deps", "3,5 MB",
  *       "80 %") and named counts from fact lines ("FAKT unindexed_findby_columns_count: 9", the key
  *       is the name). NCLOC is a unit of its own, never LOC. Per key the largest value counts.</li>
  *   <li>{@link #churn} / {@link #hotspotThreshold}: rate of change ("6 Aenderungen in 90 Tagen (Hotspot ab 5)",
  *       "Backend churn: 30") separate from it because it fluctuates and is not compared by percentage.</li>
 * </ul>
  * Basis of the worsening rule for accepted risks ({@link AuditAcceptancePolicy});
  * everything unrecognized is ignored.
 */
public final class EvidenceMetrics {
    private static final Map<String, String> CANONICAL = Map.ofEntries(
            Map.entry("loc", "LOC"), Map.entry("lines", "LOC"), Map.entry("zeilen", "LOC"),
            Map.entry("ncloc", "NCLOC"),
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

    /** Named count of a fact line, deliberately narrow: lowercase identifier with "_count", colon, number. */
    private static final Pattern NAMED_COUNT = Pattern.compile("(?<![A-Za-z0-9_])([a-z0-9_]+_count):\\s*(\\d+)\\b(?![.,]\\d)");

    private static final Pattern CHURN = Pattern.compile(
            "(?<![\\w.,])(\\d+)\\s+(?:aenderung(?:en)?|änderung(?:en)?|changes?)\\b|\\bchurn\\s*:?\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern HOTSPOT_THRESHOLD = Pattern.compile("\\bhotspot\\s+ab\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);

    private EvidenceMetrics() {}

    /** @return canonical unit or count name → largest value; empty if the text contains no measurable values. */
    public static Map<String, Double> parse(String evidence) {
        Map<String, Double> metrics = new TreeMap<>();
        if (evidence == null || evidence.isBlank()) return metrics;
        Matcher matcher = METRIC.matcher(evidence);
        while (matcher.find()) {
            String key = CANONICAL.get(matcher.group(2).toLowerCase(Locale.ROOT));
            double value = Double.parseDouble(matcher.group(1).replace(',', '.'));
            metrics.merge(key, value, Math::max);
        }
        Matcher count = NAMED_COUNT.matcher(evidence);
        while (count.find()) {
            metrics.merge(count.group(1), Double.parseDouble(count.group(2)), Math::max);
        }
        return metrics;
    }

    /** @return largest rate of change mentioned ("6 Aenderungen", "churn: 30"); empty if none is mentioned. */
    public static OptionalInt churn(String evidence) {
        if (evidence == null || evidence.isBlank()) return OptionalInt.empty();
        Matcher matcher = CHURN.matcher(evidence);
        OptionalInt result = OptionalInt.empty();
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            result = OptionalInt.of(Math.max(value, result.orElse(value)));
        }
        return result;
    }

    /** @return the hotspot threshold mentioned in the text ("Hotspot ab 5"); empty if none is mentioned. */
    public static OptionalInt hotspotThreshold(String evidence) {
        if (evidence == null) return OptionalInt.empty();
        Matcher matcher = HOTSPOT_THRESHOLD.matcher(evidence);
        return matcher.find() ? OptionalInt.of(Integer.parseInt(matcher.group(1))) : OptionalInt.empty();
    }

    /** Formats a value including unit for notes, e.g. "320 LOC" or "3.5 MB". */
    public static String format(double value, String unit) {
        String number = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        return number + (unit.equals("%") ? " %" : " " + unit);
    }
}
