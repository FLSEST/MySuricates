package com.automation.service.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der echte Spring-Kontext liefert dem Einsammler alle fuenf Quellen, in der dokumentierten
 * {@code @Order} (TODO 441a4c46). Eine neue Quelle gehoert hier dazu.
 */
@SpringBootTest
@ActiveProfiles("integration")
class PlatformVariableSourceWiringTest {

    @Autowired private List<PlatformVariableSource> sources;
    @Autowired private WorkflowContextDataService contextDataService;

    @Test
    void everySourceIsWired_inItsOrder() {
        assertThat(sources).extracting(source -> source.getClass().getSimpleName()).containsExactly(
                "PreviousReportVariable", "OpenTodosVariable", "RequestMetricsVariable", "GoalVariables",
                "AuditFindingsVariable");
    }

    /**
     * Die reservierten Namen (TODO 2a0695ff) – die Tests der Fabrik und des Sub-Workflow-Blocks schreiben sie ab;
     * waechst hier eine Quelle, muessen sie mitwachsen. Jeder bleibt ausserdem aus dem Step-Delta.
     */
    @Test
    void theCollectorReservesEveryNameOfEverySource() {
        List<String> expected = new java.util.ArrayList<>(List.of("previous_report", "open_todos", "request_metrics"));
        expected.addAll(GoalContextSource.VARIABLES);
        expected.add(AuditContextSource.VARIABLE);

        assertThat(contextDataService.platformVariableNames()).containsExactlyElementsOf(expected);
        assertThat(ContextDeltaCalculator.SYSTEM_VAR_KEYS).containsAll(contextDataService.platformVariableNames());
    }
}
