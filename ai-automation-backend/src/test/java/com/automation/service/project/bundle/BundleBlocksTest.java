package com.automation.service.project.bundle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BundleBlocksTest {

    /** Review I3 (d3a7f5e2): a tool-mode block runs an image with arguments — the import preview must show it. */
    @Test
    void shellCommands_listTheImageAndArgumentsOfAToolModeBlock() {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "shell", "config", Map.of("command", "ls")),
                Map.of("type", "shell", "config", Map.of("container_image", "aquasec/trivy:0.70.0",
                        "container_args", List.of("fs", "/workspace"))));

        assertThat(BundleBlocks.shellCommands(blocks)).containsExactly("ls", "aquasec/trivy:0.70.0 fs /workspace");
    }
}
