package com.automation.service.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der echte Spring-Kontext liefert dem Einsammler alle sechs Quellen, in der dokumentierten
 * {@code @Order} (TODO 441a4c46). Eine neue Quelle gehoert hier dazu.
 */
@SpringBootTest
@ActiveProfiles("integration")
class PlatformVariableSourceWiringTest {

    @Autowired private List<PlatformVariableSource> sources;

    @Test
    void everySourceIsWired_inItsOrder() {
        assertThat(sources).extracting(source -> source.getClass().getSimpleName()).containsExactly(
                "PreviousReportVariable", "OpenTodosVariable", "RequestMetricsVariable", "GoalVariables",
                "AuditFindingsVariable", "TodoDetailsVariable");
    }
}
