package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceRulesTest {

    private static final InstanceFacts NO_DOCKER = new InstanceFacts(false,
            "Einschalten: TEST_DOCKER_SOCKET=1, dann neu starten.", "To switch it on: TEST_DOCKER_SOCKET=1, then restart.");
    private static final InstanceFacts DOCKER = new InstanceFacts(true, "", "");

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static final List<BlockDefinition> CONTAINER_WORKFLOW = List.of(
            block("shell", "plain", Map.of("command", "set -euo pipefail\nls")),
            block("shell", "ocr", Map.of("command", "set -euo pipefail\nocr in.pdf", "container_image", "ocr:1")),
            block("switch", "pick", Map.of("expression", "x", "default", List.of(Map.of("type", "foreach",
                    "name", "each", "config", Map.of("items", List.of("a"), "execution_mode", "shell",
                            "command", "echo {{ item }}", "container_image", "alpine:3.20"))))));

    private static List<AnalyzerFinding> rule(List<AnalyzerFinding> findings) {
        return findings.stream().filter(f -> f.rule().equals("container-without-docker")).toList();
    }

    @Test
    @DisplayName("without Docker every container block is a HIGH finding with the switch hint, nested ones too")
    void reportsContainerBlocksWithoutDocker() {
        List<AnalyzerFinding> findings = rule(analyzer.analyze(CONTAINER_WORKFLOW, null, Map.of(), NO_DOCKER));

        assertThat(findings).hasSize(2).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.detail()).contains("TEST_DOCKER_SOCKET=1");
            assertThat(f.detailEn()).contains("cannot reach a Docker daemon").contains("TEST_DOCKER_SOCKET=1");
        });
        assertThat(findings.get(0).blockIndex()).isEqualTo(1);
        assertThat(findings.get(0).detailEn()).startsWith("uses container_image (ocr:1)");
        assertThat(findings.get(1).blockIndex()).isEqualTo(2);
        assertThat(findings.get(1).detailEn()).startsWith("Block 3 › default › Block 1: uses container_image (alpine:3.20)");
    }

    @Test
    @DisplayName("a reachable Docker raises nothing")
    void silentWithDocker() {
        assertThat(rule(analyzer.analyze(CONTAINER_WORKFLOW, null, Map.of(), DOCKER))).isEmpty();
    }

    @Test
    @DisplayName("without instance facts the rule stays out, so pool gates do not depend on the test machine")
    void silentWithoutInstanceFacts() {
        assertThat(rule(analyzer.analyze(CONTAINER_WORKFLOW, null, Map.of()))).isEmpty();
        assertThat(rule(analyzer.analyze(CONTAINER_WORKFLOW, null))).isEmpty();
    }

    @Test
    @DisplayName("a workflow without container blocks raises nothing, even without Docker")
    void silentWithoutContainerBlocks() {
        List<BlockDefinition> host = List.of(block("shell", "plain", Map.of("command", "set -euo pipefail\nls",
                "container_image", "  ")));

        assertThat(rule(analyzer.analyze(host, null, Map.of(), NO_DOCKER))).isEmpty();
    }
}
