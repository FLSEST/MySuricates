package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunTokenRulesTest {

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

    private static BlockDefinition shell(String command) {
        BlockDefinition b = new BlockDefinition();
        b.setType("shell");
        b.setName("reader");
        b.setConfig(new HashMap<>(Map.of("command", "set -euo pipefail\n" + command)));
        return b;
    }

    private List<String> rules(BlockDefinition block, Map<String, Object> metadata) {
        return analyzer.analyze(List.of(block), null, metadata).stream().map(AnalyzerFinding::rule).toList();
    }

    @Test
    @DisplayName("using the run token without asking for it is a MEDIUM finding")
    void usedButNotRequested() {
        var findings = analyzer.analyze(List.of(shell("curl -s -H \"X-Run-Token: $MYSURICATES_RUN_TOKEN\" \"$MYSURICATES_API/todos\"")),
                null, Map.of());

        assertThat(findings).filteredOn(f -> f.rule().equals("run-token-not-requested"))
                .singleElement().satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.MEDIUM);
                    assertThat(f.detailEn()).contains("metadata.permissions");
                });
    }

    @Test
    @DisplayName("asking for it with permissions read clears the finding, and the call is no self-api-call")
    void requestedIsFine() {
        List<String> rules = rules(shell("curl -s --noproxy '*' -H \"X-Run-Token: $MYSURICATES_RUN_TOKEN\" \"$MYSURICATES_API/todos\""),
                Map.of("permissions", List.of("read")));

        assertThat(rules).doesNotContain("run-token-not-requested", "self-api-call");
    }

    @Test
    @DisplayName("a block that does not use the token is not touched")
    void unrelatedBlock() {
        assertThat(rules(shell("echo hello"), Map.of())).doesNotContain("run-token-not-requested");
    }
}
