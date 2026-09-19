package com.automation.service.workflow.analyzer;

/**
 * Ein Baufehler-Befund an einer Workflow-Definition.
 *
 * @param rule      stabiler Schlüssel der Regel, für Filter und spätere TODO-Promotion
 * @param severity  {@code HIGH} kostet Geld oder bricht den Lauf, {@code MEDIUM} kostet Laufzeit
 *                  oder Kontext, {@code LOW} ist Hygiene
 * @param blockIndex 0-basierter Index des betroffenen Blocks, {@code -1} für die Definition als Ganzes
 * @param blockName  Name des Blocks, falls gesetzt — sonst {@code null}
 * @param detail    was konkret gefunden wurde, in einem Satz und mit dem Fundstück
 */
public record AnalyzerFinding(String rule, Severity severity, int blockIndex, String blockName, String detail) {

    public enum Severity { HIGH, MEDIUM, LOW }

    static AnalyzerFinding of(String rule, Severity severity, int index, String name, String detail) {
        return new AnalyzerFinding(rule, severity, index, name, detail);
    }
}
