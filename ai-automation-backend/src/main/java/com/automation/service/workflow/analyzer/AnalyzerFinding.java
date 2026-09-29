package com.automation.service.workflow.analyzer;

/**
 * Ein Baufehler-Befund an einer Workflow-Definition.
 *
 * <p><b>Zweisprachig</b> (TODO 81773d7b): {@code detail} bleibt deutsch — so lesen ihn ältere Clients, das
 * Activity-Log und {@code PoolAnalyzerGateTest} —, {@code detailEn} trägt denselben Satz auf Englisch. Jede Regel
 * liefert beide; {@link #of} verlangt sie, und {@code AnalyzerFindingLanguagesTest} löst jede Regel aus und prüft
 * beide Texte. Die Oberfläche wählt nach ihrer Sprache, der Assistent nach {@code language} der Anfrage
 * ({@link #detail(String)}).
 *
 * @param rule      stabiler Schlüssel der Regel, für Filter und spätere TODO-Promotion
 * @param severity  {@code HIGH} kostet Geld oder bricht den Lauf, {@code MEDIUM} kostet Laufzeit
 *                  oder Kontext, {@code LOW} ist Hygiene
 * @param blockIndex 0-basierter Index des betroffenen Blocks, {@code -1} für die Definition als Ganzes
 * @param blockName  Name des Blocks, falls gesetzt — sonst {@code null}
 * @param detail    was konkret gefunden wurde, in einem Satz und mit dem Fundstück — deutsch
 * @param detailEn  derselbe Satz auf Englisch; {@code null} nur bei Befunden, die nicht aus einer Regel stammen
 */
public record AnalyzerFinding(String rule, Severity severity, int blockIndex, String blockName, String detail,
                              String detailEn) {

    public enum Severity { HIGH, MEDIUM, LOW }

    /** Ohne englischen Text — für Befunde von außerhalb der Regeln (Tests, Fixtures); die Regeln nutzen {@link #of}. */
    public AnalyzerFinding(String rule, Severity severity, int blockIndex, String blockName, String detail) {
        this(rule, severity, blockIndex, blockName, detail, null);
    }

    /** Der Weg jeder Regel: beide Sprachen, keine davon leer. */
    static AnalyzerFinding of(String rule, Severity severity, int index, String name, String detail, String detailEn) {
        if (detail == null || detail.isBlank() || detailEn == null || detailEn.isBlank()) {
            throw new IllegalArgumentException("Befund " + rule + " braucht detail und detailEn");
        }
        return new AnalyzerFinding(rule, severity, index, name, detail, detailEn);
    }

    /** Der Text in {@code language} ({@code "en"}, sonst deutsch); fehlt der englische, der deutsche. */
    public String detail(String language) {
        return "en".equalsIgnoreCase(language) && detailEn != null && !detailEn.isBlank() ? detailEn : detail;
    }
}
