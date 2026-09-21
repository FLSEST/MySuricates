package com.automation.service.workflow.analyzer;

import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerFindingTest {

    @Test
    void ofUebernimmtAlleFelder() {
        AnalyzerFinding f = AnalyzerFinding.of("r", Severity.LOW, -1, null, "d");
        assertThat(f).isEqualTo(new AnalyzerFinding("r", Severity.LOW, -1, null, "d"));
    }

    /** Controller und WorkflowAnalyzer filtern/sortieren über die Ordinalzahl: HIGH ist die schwerste. */
    @Test
    void severityReihenfolgeVonSchwerNachLeicht() {
        assertThat(Severity.values()).containsExactly(Severity.HIGH, Severity.MEDIUM, Severity.LOW);
        List<Severity> sorted = Arrays.stream(new Severity[]{Severity.LOW, Severity.HIGH, Severity.MEDIUM})
                .sorted().toList();
        assertThat(sorted).containsExactly(Severity.HIGH, Severity.MEDIUM, Severity.LOW);
    }

    @Test
    void jsonRundreise() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AnalyzerFinding f = new AnalyzerFinding("unwired-output", Severity.MEDIUM, 2, "report", "detail");

        String json = mapper.writeValueAsString(f);

        assertThat(json).contains("\"severity\":\"MEDIUM\"").contains("\"blockIndex\":2");
        assertThat(mapper.readValue(json, AnalyzerFinding.class)).isEqualTo(f);
    }
}
