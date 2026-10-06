package com.automation.service.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real Spring context supplies the collector with all six sources, in the documented
 * {@code @Order} (TODO 441a4c46). A new source belongs here.
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
                "AuditFindingsVariable", "TodoDetailsVariable");
    }

    /**
     * The reserved names (TODO 2a0695ff) – the tests of the factory and the sub-workflow block copy them;
     * if a source grows here, they must grow too. Each also stays out of the step delta.
     */
    @Test
    void theCollectorReservesEveryNameOfEverySource() {
        List<String> expected = new java.util.ArrayList<>(List.of("previous_report", "open_todos", "request_metrics"));
        expected.addAll(GoalContextSource.VARIABLES);
        expected.add(AuditContextSource.VARIABLE);
        expected.add(TodoDetailsVariable.NAME);

        assertThat(contextDataService.platformVariableNames()).containsExactlyElementsOf(expected);
        assertThat(ContextDeltaCalculator.SYSTEM_VAR_KEYS).containsAll(contextDataService.platformVariableNames());
    }
}
