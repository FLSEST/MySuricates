package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one answer to "which workflow does this block start" (TODO 49558f78). */
class WorkflowStartersTest {

    @Test
    void subWorkflow_namesItsChildUnderTheCurrentAndTheLegacyKey() {
        assertThat(WorkflowStarters.startedWorkflowIds("sub-workflow", Map.of("workflow_id", "w-1")))
                .containsExactly(Map.entry("workflow_id", "w-1"));
        assertThat(WorkflowStarters.startedWorkflowIds("sub-workflow", Map.of("workflowId", "w-2")))
                .containsExactly(Map.entry("workflowId", "w-2"));
        assertThat(WorkflowStarters.startedWorkflowIds("sub-workflow", Map.of("workflow_id", "w-1", "workflowId", "w-2")))
                .containsExactly(Map.entry("workflow_id", "w-1"), Map.entry("workflowId", "w-2"));
    }

    /** ForEachBlockExecutor reads only workflow_id; the legacy key starts nothing there. */
    @Test
    void foreach_startsAWorkflowPerItem_onlyByWorkflowId() {
        assertThat(WorkflowStarters.startedWorkflowIds("foreach", Map.of("items", "a", "workflow_id", "w-1")))
                .containsExactly(Map.entry("workflow_id", "w-1"));
        assertThat(WorkflowStarters.startedWorkflowIds("foreach", Map.of("workflowId", "w-2"))).isEmpty();
    }

    @Test
    void otherTypesBlankValuesAndMissingConfig_startNothing() {
        assertThat(WorkflowStarters.startedWorkflowIds("shell", Map.of("workflow_id", "w-1"))).isEmpty();
        assertThat(WorkflowStarters.startedWorkflowIds(null, Map.of("workflow_id", "w-1"))).isEmpty();
        assertThat(WorkflowStarters.startedWorkflowIds("sub-workflow", Map.of("workflow_id", "  ", "workflowId", 42))).isEmpty();
        assertThat(WorkflowStarters.startedWorkflowIds("foreach", null)).isEmpty();
    }

    /** Placeholders are kept — whether one counts is up to the caller (bundle export skips them). */
    @Test
    void placeholders_areReturnedAsTheyStand() {
        assertThat(WorkflowStarters.startedWorkflowIds("foreach", Map.of("workflow_id", "{{ child_id }}")))
                .containsExactly(Map.entry("workflow_id", "{{ child_id }}"));
    }

    @Test
    void theResultIsACopy_soTheCallerMayRewriteTheConfigWhileGoingThroughIt() {
        Map<String, Object> config = new HashMap<>(Map.of("workflow_id", "w-1", "workflowId", "w-2"));

        WorkflowStarters.startedWorkflowIds("sub-workflow", config).forEach((key, id) -> config.put(key, "ref:" + id));

        assertThat(config).containsEntry("workflow_id", "ref:w-1").containsEntry("workflowId", "ref:w-2");
    }
}
