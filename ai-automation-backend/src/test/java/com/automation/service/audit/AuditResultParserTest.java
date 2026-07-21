package com.automation.service.audit;

import com.automation.entity.AuditSeverity;
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
}
