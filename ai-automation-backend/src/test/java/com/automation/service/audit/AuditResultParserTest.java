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

    /** A truncated snapshot previously resolved all findings that were not delivered (TODO e39379a9). */
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

    /** Backward compatible: without the field everything stays as before. */
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
     * previous_report is the first 3000 characters of the previous run, for short reports including the envelope. If the
     * model repeats it before writing its own, the old snapshot would count with the first envelope
     * (TODO 1cf996df). Its own one comes last according to the prompt.
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

    /** One envelope: as before, the text after it also stays in the markdown. */
    @Test
    void oneEnvelope_behavesAsBefore_withTextOnBothSides() {
        var result = parser.parse("Einleitung\n" + envelopeWith("size.class", "A.java") + "\nNachsatz");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("A.java"));
        assertThat(result.markdown()).isEqualTo("Einleitung\n\nNachsatz");
    }

    /** The repeated previous run is cut off at 3000 characters, so its envelope is open. */
    @Test
    void truncatedEchoBeforeTheOwnEnvelope_doesNotSpoilIt() {
        String echo = "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":"
                + "\"size.class\",\"subject\":\"Old.java\"\n[...gekürzt]";
        var result = parser.parse(echo + "\n\n# Neuer Report\n" + envelopeWith("size.class", "New.java"));

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("New.java"));
    }

    /** If the own answer is truncated, no envelope applies – least of all the repeated old one. */
    @Test
    void cutOffOwnEnvelope_doesNotFallBackToAnEarlierOne() {
        String output = envelopeWith("size.class", "Old.java") + "\n\n# Neuer Report\n"
                + "<!-- mysuricates:audit-result:v1\n{\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size.cl";

        var result = parser.parse(output);

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isEqualTo("The last audit result envelope v1 is incomplete or unreadable");
        assertThat(result.findings()).isEmpty();
    }

    /** An envelope start in the text of a finding is not an envelope of its own. */
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
     * If the model quotes the start including the JSON beginning in the text of a finding, this candidate extends to the
     * end of the own envelope, but is not JSON (not even with empty braces): the enclosing one applies.
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

    /** A trailing sentence that quotes the marker without JSON does not start an envelope: valid as before. */
    @Test
    void quotedMarkerAfterTheEnvelope_withoutJson_isNoEnvelope() {
        var result = parser.parse(envelopeWith("size.class", "A.java")
                + "\nDer Envelope beginnt mit `<!-- mysuricates:audit-result:v1`.");

        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("A.java"));
        assertThat(result.markdown()).isEqualTo("Der Envelope beginnt mit `<!-- mysuricates:audit-result:v1`.");
    }

    /** Marker and JSON beginning after the last complete envelope: an open envelope, no fallback. */
    @Test
    void startWithJsonAfterTheLastCompleteEnvelope_makesTheReportInvalid() {
        var result = parser.parse(envelopeWith("size.class", "A.java")
                + "\nNachtrag: <!-- mysuricates:audit-result:v1 {\"snapshotComplete\":");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).isEqualTo("The last audit result envelope v1 is incomplete or unreadable");
    }

    /** Unreadable JSON in the only envelope: invalid, and the JSON parser's reason stays visible. */
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
