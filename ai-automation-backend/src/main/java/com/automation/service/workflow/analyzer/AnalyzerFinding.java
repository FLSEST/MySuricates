package com.automation.service.workflow.analyzer;

/**
 * A construction-error finding on a workflow definition.
 *
 * <p><b>Bilingual</b> (TODO 81773d7b): {@code detail} stays German - that is how older clients, the
 * activity log and {@code PoolAnalyzerGateTest} read it -, {@code detailEn} carries the same sentence in English. Every rule
 * supplies both; {@link #of} requires them, and {@code AnalyzerFindingLanguagesTest} triggers every rule and checks
 * both texts. The UI chooses by its language, the assistant by the request's {@code language}
 * ({@link #detail(String)}).
 *
 * @param rule      stable key of the rule, for filters and later TODO promotion
 * @param severity  {@code HIGH} costs money or breaks the run, {@code MEDIUM} costs runtime
 *                  or context, {@code LOW} is hygiene
 * @param blockIndex 0-based index of the affected block, {@code -1} for the definition as a whole
 * @param blockName  name of the block if set - otherwise {@code null}
 * @param detail    what exactly was found, in one sentence and with the evidence - German
 * @param detailEn  the same sentence in English; {@code null} only for findings that do not come from a rule
 */
public record AnalyzerFinding(String rule, Severity severity, int blockIndex, String blockName, String detail,
                              String detailEn) {

    public enum Severity { HIGH, MEDIUM, LOW }

    /** Without English text - for findings from outside the rules (tests, fixtures); the rules use {@link #of}. */
    public AnalyzerFinding(String rule, Severity severity, int blockIndex, String blockName, String detail) {
        this(rule, severity, blockIndex, blockName, detail, null);
    }

    /** The path of every rule: both languages, neither of them empty. */
    static AnalyzerFinding of(String rule, Severity severity, int index, String name, String detail, String detailEn) {
        if (detail == null || detail.isBlank() || detailEn == null || detailEn.isBlank()) {
            throw new IllegalArgumentException("Befund " + rule + " braucht detail und detailEn");
        }
        return new AnalyzerFinding(rule, severity, index, name, detail, detailEn);
    }

    /** The text in {@code language} ({@code "en"}, otherwise German); if the English one is missing, the German one. */
    public String detail(String language) {
        return "en".equalsIgnoreCase(language) && detailEn != null && !detailEn.isBlank() ? detailEn : detail;
    }
}
