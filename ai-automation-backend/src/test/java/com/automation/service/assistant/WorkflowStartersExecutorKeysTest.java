package com.automation.service.assistant;

import com.automation.executor.WorkflowStarters;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keys {@link WorkflowStarters} names for a starter type are the ones its executor reads — otherwise
 * bundle export and sync hints would look for workflow ids under keys no run uses (TODO 49558f78).
 */
class WorkflowStartersExecutorKeysTest {

    @ParameterizedTest
    @ValueSource(strings = {WorkflowStarters.SUB_WORKFLOW, WorkflowStarters.FOREACH})
    void everyKeyAStarterIsSearchedUnder_isReadByItsExecutor(String type) throws IOException {
        assertThat(ExecutorConfigReads.types()).contains(type);
        assertThat(WorkflowStarters.workflowIdKeys(type)).isNotEmpty();
        assertThat(ExecutorConfigReads.literals(type)).containsAll(WorkflowStarters.workflowIdKeys(type));
    }
}
