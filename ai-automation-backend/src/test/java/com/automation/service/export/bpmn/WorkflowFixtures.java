package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    static Workflow workflow(String name, List<BlockDefinition> blocks) {
        return Workflow.builder().id("wf-test").name(name).blocks(blocks).build();
    }

    static Workflow linear() {
        return workflow("Linear Demo", List.of(
                block("shell", "Daten sammeln", Map.of("command", "git log --oneline -20")),
                block("simple", null, Map.of("command", "Fasse die Historie zusammen:\n{{ previous_output }}")),
                block("approval", null, Map.of("question", "Bericht freigeben?"))));
    }
}
