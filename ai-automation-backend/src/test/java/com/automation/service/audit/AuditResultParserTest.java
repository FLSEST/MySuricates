package com.automation.service.audit;

import com.automation.entity.AuditSeverity;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import com.automation.testsupport.TurkishLocale;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditResultParserTest {
    private final AuditResultParser parser = new AuditResultParser(new ObjectMapper());

    @Test
    void parsesCompleteSnapshotAndRemovesEnvelopeFromMarkdown() {
        String output = """
                # Security report

                Details remain visible.

                <!-- mysuricates:audit-result:v1
                {"snapshotComplete":true,"findings":[{
                  "ruleKey":"security.secret",
                  "subject":"application.yml",
                  "severity":"HIGH",
                  "title":"Secret in configuration",
                  "description":"Move it to the environment",
                  "evidence":"application.yml:12",
                  "tags":["backend","security"]
                }]}
                -->
                """;

        var result = parser.parse(output);

        assertThat(result.valid()).isTrue();
        assertThat(result.markdown()).isEqualTo("# Security report\n\nDetails remain visible.");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.ruleKey()).isEqualTo("security.secret");
            assertThat(finding.subject()).isEqualTo("application.yml");
            assertThat(finding.severity()).isEqualTo(AuditSeverity.HIGH);
            assertThat(finding.tags()).containsExactly("backend", "security");
        });
    }

    @Test
    void acceptsEmptyCompleteSnapshot() {
        var result = parser.parse("Report\n<!-- mysuricates:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[]}\n-->");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void rejectsPartialSnapshot() {
        var result = parser.parse("<!-- mysuricates:audit-result:v1\n"
                + "{\"snapshotComplete\":false,\"findings\":[]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("snapshotComplete");
    }

    @Test
    void rejectsMissingStableIdentity() {
        var result = parser.parse("<!-- mysuricates:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[{\"severity\":\"LOW\",\"title\":\"x\"}]}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("ruleKey");
    }

    @Test
    void rejectsOutputWithoutEnvelope() {
        var result = parser.parse("# Normal workflow output");

        assertThat(result.valid()).isFalse();
        assertThat(result.markdown()).isEqualTo("# Normal workflow output");
    }

    private static String envelope(String extraField, int findingCount) {
        StringBuilder findings = new StringBuilder();
        for (int i = 0; i < findingCount; i++) {
            if (i > 0) findings.append(",");
            findings.append("{\"ruleKey\":\"size.class\",\"subject\":\"C").append(i)
                    .append(".java\",\"severity\":\"LOW\",\"title\":\"t").append(i).append("\"}");
        }
        return "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true" + extraField
                + ",\"findings\":[" + findings + "]}\n-->";
    }

    /** Ein gekuerzter Snapshot loeste zuvor alle nicht gelieferten Findings auf (TODO e39379a9). */
    @Test
    void rejectsEnvelopeThatDeliversFewerFindingsThanPromised() {
        var result = parser.parse(envelope(",\"expectedFindings\":5", 2));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("expectedFindings says 5").contains("carries 2");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void acceptsEnvelopeWhosePromiseMatches() {
        var result = parser.parse(envelope(",\"expectedFindings\":2", 2));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void rejectsNonNumericPromise() {
        var result = parser.parse(envelope(",\"expectedFindings\":\"viele\"", 2));

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("expectedFindings must be a number");
    }

    /** Rueckwaertskompatibel: ohne das Feld bleibt alles wie bisher. */
    @Test
    void envelopeWithoutPromiseStaysValid() {
        var result = parser.parse(envelope("", 2));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).hasSize(2);
    }

    @Test
    void leererSnapshotMitNullVersprechenIstGueltig() {
        var result = parser.parse(envelope(",\"expectedFindings\":0", 0));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    private static String envelopeWith(String ruleKey, String subject) {
        return "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"" + ruleKey
                + "\",\"subject\":\"" + subject + "\",\"severity\":\"MEDIUM\",\"title\":\"t\"}]}\n-->";
    }

    /**
     * previous_report sind die ersten 3000 Zeichen des Vorlaufs, bei kurzen Reports samt Envelope. Gibt das
     * Modell ihn wieder, bevor es seinen eigenen schreibt, zaehlte mit dem ersten Envelope der alte Snapshot
     * (TODO 1cf996df). Der eigene steht laut Prompt am Ende.
     */
    @Test
    void twoEnvelopes_theLastOneCounts_andNeitherStaysInTheMarkdown() {
        String output = "Wiedergabe des Vorlaufs:\n" + envelopeWith("size.class", "Old.java")
                + "\n\n# Neuer Report\n" + envelopeWith("size.class", "New.java");

        var result = parser.parse(output);

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("New.java"));
        assertThat(result.markdown()).isEqualTo("Wiedergabe des Vorlaufs:\n\n\n# Neuer Report")
                .doesNotContain("mysuricates:audit-result");
    }

    /** Ein Envelope: wie bisher, auch der Text dahinter bleibt im Markdown. */
    @Test
    void oneEnvelope_behavesAsBefore_withTextOnBothSides() {
        var result = parser.parse("Einleitung\n" + envelopeWith("size.class", "A.java") + "\nNachsatz");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("A.java"));
        assertThat(result.markdown()).isEqualTo("Einleitung\n\nNachsatz");
    }

    /** Der wiedergegebene Vorlauf ist bei 3000 Zeichen abgeschnitten, sein Envelope also offen. */
    @Test
    void truncatedEchoBeforeTheOwnEnvelope_doesNotSpoilIt() {
        String echo = "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":"
                + "\"size.class\",\"subject\":\"Old.java\"\n[...gekürzt]";
        var result = parser.parse(echo + "\n\n# Neuer Report\n" + envelopeWith("size.class", "New.java"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("New.java"));
    }

    /** Ist die eigene Antwort abgeschnitten, gilt kein Envelope – schon gar nicht der wiedergegebene alte. */
    @Test
    void cutOffOwnEnvelope_doesNotFallBackToAnEarlierOne() {
        String output = envelopeWith("size.class", "Old.java") + "\n\n# Neuer Report\n"
                + "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size.cl";

        var result = parser.parse(output);

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isEqualTo("The last audit result envelope v1 is incomplete or unreadable");
        assertThat(result.findings()).isEmpty();
    }

    /** Ein Envelope-Anfang im Text eines Findings ist kein eigener Envelope. */
    @Test
    void envelopeStartInsideAFinding_isNoEnvelopeOfItsOwn() {
        String output = "# Report\n<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{"
                + "\"ruleKey\":\"sec.xss\",\"subject\":\"Report.tsx\",\"severity\":\"LOW\",\"title\":\"t\","
                + "\"description\":\"Zeigt <!-- mysuricates:audit-result:v1 aus Nutzertext an\"}]}\n-->";

        var result = parser.parse(output);

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.description()).contains("aus Nutzertext"));
        assertThat(result.markdown()).isEqualTo("# Report");
    }

    private static String envelopeDescribing(String description) {
        return "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"sec.xss\","
                + "\"subject\":\"Report.tsx\",\"severity\":\"LOW\",\"title\":\"t\",\"description\":\""
                + description + "\"},{\"ruleKey\":\"sec.xss\",\"subject\":\"Other.tsx\",\"severity\":\"LOW\","
                + "\"title\":\"t\"}]}\n-->";
    }

    /**
     * Zitiert das Modell den Anfang samt JSON-Beginn im Text eines Findings, reicht dieser Kandidat bis zum
     * Ende des eigenen Envelopes, ist aber kein JSON (auch nicht mit leeren Klammern): Es gilt der umschliessende.
     */
    @Test
    void quotedStartWithJsonInsideAFinding_keepsTheEnclosingEnvelope() {
        for (String quote : new String[] {"Zeigt <!-- mysuricates:audit-result:v1 {\\\"a\\\":1 an",
                "Zeigt <!-- mysuricates:audit-result:v1 {} an"}) {
            var result = parser.parse("# Report\n" + envelopeDescribing(quote));

            assertThat(result.valid()).as(quote).isTrue();
            assertThat(result.findings()).as(quote).extracting(ParsedFinding::subject)
                    .containsExactly("Report.tsx", "Other.tsx");
        }
    }

    /** Ein Nachsatz, der den Marker ohne JSON zitiert, beginnt keinen Envelope: gueltig wie vorher. */
    @Test
    void quotedMarkerAfterTheEnvelope_withoutJson_isNoEnvelope() {
        var result = parser.parse(envelopeWith("size.class", "A.java")
                + "\nDer Envelope beginnt mit `<!-- mysuricates:audit-result:v1`.");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("A.java"));
        assertThat(result.markdown()).isEqualTo("Der Envelope beginnt mit `<!-- mysuricates:audit-result:v1`.");
    }

    /** Marker und JSON-Beginn hinter dem letzten vollstaendigen Envelope: ein offener Envelope, kein Rueckgriff. */
    @Test
    void startWithJsonAfterTheLastCompleteEnvelope_makesTheReportInvalid() {
        var result = parser.parse(envelopeWith("size.class", "A.java")
                + "\nNachtrag: <!-- mysuricates:audit-result:v1 {\"snapshotComplete\":");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isEqualTo("The last audit result envelope v1 is incomplete or unreadable");
    }

    /** Unlesbares JSON im einzigen Envelope: ungueltig, und der Grund des JSON-Parsers bleibt sichtbar. */
    @Test
    void unreadableJson_isInvalid_andNamesTheParserReason() {
        var result = parser.parse("<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[],}\n-->");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).startsWith("The last audit result envelope v1 is incomplete or unreadable: ")
                .contains("Unexpected character");
        assertThat(result.markdown()).isEmpty();
    }

    /** 80796b2d: under tr-TR "critical".toUpperCase() is "CRİTİCAL" and the envelope was rejected. */
    @Test
    void severity_doesNotDependOnTheDefaultLocale() {
        String output = "Report\n<!-- mysuricates:audit-result:v1\n"
                + "{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"r\",\"subject\":\"s\","
                + "\"severity\":\"critical\",\"title\":\"t\"}]}\n-->";

        var result = TurkishLocale.call(() -> parser.parse(output));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(AuditSeverity.CRITICAL));
    }
}
