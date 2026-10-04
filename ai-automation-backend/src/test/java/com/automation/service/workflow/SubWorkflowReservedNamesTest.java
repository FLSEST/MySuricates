package com.automation.service.workflow;

import com.automation.executor.blocks.SubWorkflowLauncher;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * foreach's {@code context} may not forge a value the platform sets for every run (review a5c9fbbf):
 * every system key {@link ContextDeltaCalculator} knows is refused, so a new one cannot slip past.
 */
class SubWorkflowReservedNamesTest {

    /** What WorkflowContextDataService.platformVariableNames() collects from the platform sources. */
    private static Set<String> platformNames() {
        Set<String> names = new HashSet<>(Set.of("previous_report", "open_todos", "request_metrics",
                AuditContextSource.VARIABLE, TodoDetailsVariable.NAME));
        names.addAll(GoalContextSource.VARIABLES);
        return names;
    }

    @Test
    void everySystemVariableIsReserved() {
        for (String name : ContextDeltaCalculator.SYSTEM_VAR_KEYS) {
            assertThat(SubWorkflowLauncher.reservedReason(name, "item", platformNames())).as(name).isNotNull();
        }
        assertThat(SubWorkflowLauncher.reservedReason("feature_id", "item", Set.of())).isNull();
        assertThat(SubWorkflowLauncher.reservedReason("item_name", "item", Set.of())).isNull();
    }
}
