package com.automation.service.audit;

import com.automation.entity.AuditSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuditTodoThresholdTest {

    @ParameterizedTest(name = "{0} admits {1}: {2}")
    @CsvSource({
            "CRITICAL, CRITICAL, true", "CRITICAL, HIGH, false",
            "HIGH, CRITICAL, true", "HIGH, HIGH, true", "HIGH, MEDIUM, false",
            "MEDIUM, HIGH, true", "MEDIUM, MEDIUM, true", "MEDIUM, LOW, false",
            "LOW, LOW, true", "LOW, CRITICAL, true",
            "NONE, CRITICAL, false", "NONE, LOW, false"})
    void admitsFindingsAtOrAboveTheThreshold(AuditTodoThreshold threshold, AuditSeverity severity, boolean admitted) {
        assertThat(threshold.admits(severity)).isEqualTo(admitted);
    }

    @Test
    void aFindingWithoutSeverityIsNeverAdmitted() {
        assertThat(AuditTodoThreshold.LOW.admits(null)).isFalse();
    }

    @Test
    void theDefaultIsTheFormerFixedThreshold() {
        assertThat(AuditTodoThreshold.DEFAULT).isEqualTo(AuditTodoThreshold.HIGH);
    }

    @Test
    void parse_acceptsEveryNameInAnyCase_withoutAValueTheDefault_andRejectsTheRest() {
        assertThat(AuditTodoThreshold.parse(null)).contains(AuditTodoThreshold.HIGH);
        assertThat(AuditTodoThreshold.parse("  ")).contains(AuditTodoThreshold.HIGH);
        assertThat(AuditTodoThreshold.parse(" low ")).contains(AuditTodoThreshold.LOW);
        assertThat(AuditTodoThreshold.parse("None")).contains(AuditTodoThreshold.NONE);
        assertThat(AuditTodoThreshold.parse("sometimes")).isEmpty();
        assertThat(AuditTodoThreshold.parse(3)).isEmpty();
    }
}
