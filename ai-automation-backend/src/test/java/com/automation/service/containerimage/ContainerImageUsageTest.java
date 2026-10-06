package com.automation.service.containerimage;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.containerimage.ContainerImageUsage.Mode;
import com.automation.service.containerimage.ContainerImageUsage.WorkflowUse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerImageUsageTest {

    @Test
    void shellBlockWithImageIsACommandUse() {
        Workflow workflow = workflow("w1", "Build", block("build", Map.of("command", "make", "container_image", "automation-agent-java:latest")));

        Map<String, List<WorkflowUse>> uses = ContainerImageUsage.scan(List.of(workflow));

        assertThat(uses).containsOnlyKeys("automation-agent-java:latest");
        assertThat(uses.get("automation-agent-java:latest"))
                .containsExactly(new WorkflowUse("w1", "Build", "build", Mode.COMMAND));
    }

    @Test
    void containerArgsMeanToolModeAndAMissingTagMeansLatest() {
        Workflow workflow = workflow("w2", "Security", block("trivy", Map.of(
                "container_image", "automation-agent-devops", "container_args", List.of("trivy", "fs", "/workspace"))));

        assertThat(ContainerImageUsage.scan(List.of(workflow)).get("automation-agent-devops:latest"))
                .containsExactly(new WorkflowUse("w2", "Security", "trivy", Mode.TOOL));
    }

    @Test
    void nestedBlocksAreFoundAndNamedAfterTheirParent() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("type", "shell");
        inner.put("name", "scan-one");
        inner.put("config", Map.of("command", "ls", "container_image", "alpine:3"));
        Map<String, Object> switchConfig = Map.of("cases", List.of(Map.of("value", "x", "blocks", List.of(inner))));
        Workflow workflow = workflow("w3", "Loop", block("switcher", switchConfig),
                block(null, Map.of("label", "Retry im Container", "execution_mode", "shell",
                        "container_image", "automation-agent-node:latest", "command", "npm test")));

        Map<String, List<WorkflowUse>> uses = ContainerImageUsage.scan(List.of(workflow));

        assertThat(uses.get("alpine:3")).containsExactly(new WorkflowUse("w3", "Loop", "switcher › scan-one", Mode.COMMAND));
        assertThat(uses.get("automation-agent-node:latest"))
                .containsExactly(new WorkflowUse("w3", "Loop", "Retry im Container", Mode.COMMAND));
    }

    @Test
    void blocksWithoutImageOrWithBlankImageAreIgnored() {
        Workflow workflow = workflow("w4", "Plain", block("a", Map.of("command", "echo")),
                block("b", Map.of("command", "echo", "container_image", " ")));

        assertThat(ContainerImageUsage.scan(List.of(workflow))).isEmpty();
    }

    @Test
    void unnamedBlockWithoutLabelGetsItsPosition() {
        Workflow workflow = workflow("w5", "Anon", block(null, Map.of("command", "x")),
                block(null, Map.of("command", "y", "container_image", "busybox:1")));

        assertThat(ContainerImageUsage.scan(List.of(workflow)).get("busybox:1").getFirst().block()).isEqualTo("#2");
    }

    private static Workflow workflow(String id, String name, BlockDefinition... blocks) {
        Workflow workflow = new Workflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setBlocks(new ArrayList<>(List.of(blocks)));
        return workflow;
    }

    private static BlockDefinition block(String name, Map<String, Object> config) {
        BlockDefinition block = new BlockDefinition();
        block.setType("shell");
        block.setName(name);
        block.setConfig(new HashMap<>(config));
        return block;
    }
}
