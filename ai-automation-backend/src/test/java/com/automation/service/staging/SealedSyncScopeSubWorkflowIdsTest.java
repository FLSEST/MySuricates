package com.automation.service.staging;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Which workflows a sealed definition starts — the search behind the sync hints (TODO 49558f78). */
class SealedSyncScopeSubWorkflowIdsTest {

    @Test
    void findsEveryWorkflowTheDefinitionStarts_atTopLevelAndNested() {
        Workflow w = Workflow.builder().name("w-x").build();
        w.setBlocks(new ArrayList<>(List.of(
                BlockDefinition.builder().type("foreach").config(new HashMap<>(Map.of("workflow_id", "w-a"))).build(),
                BlockDefinition.builder().type("switch").config(new HashMap<>(Map.of("default", List.of(
                        Map.of("type", "foreach", "config", Map.of("workflow_id", "w-b")),
                        Map.of("type", "sub-workflow", "config", Map.of("workflowId", "w-c")))))).build(),
                BlockDefinition.builder().type("shell").config(new HashMap<>(Map.of("workflow_id", "w-not-started"))).build())));

        assertThat(SealedSyncScope.subWorkflowIds(w)).containsExactly("w-a", "w-b", "w-c");
    }

    @Test
    void aDefinitionWithoutBlocks_startsNothing() {
        assertThat(SealedSyncScope.subWorkflowIds(Workflow.builder().name("empty").build())).isEmpty();
    }
}
