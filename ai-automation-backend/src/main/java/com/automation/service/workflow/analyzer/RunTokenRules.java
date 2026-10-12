package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import com.automation.util.RunAccess;

import java.util.List;
import java.util.Map;

/**
 * {@code run-token-not-requested} (TODO cfb5d56b): a block uses the run token — {@code $MYSURICATES_RUN_TOKEN},
 * {@code $MYSURICATES_API} or the {@code X-Run-Token} header — but the workflow does not ask for one with
 * {@code metadata.permissions: ["read"]}. The run then gets no token, and the call fails with 401 at run time.
 */
final class RunTokenRules {

    private static final List<String> MARKERS = List.of(RunAccess.TOKEN_ENV, RunAccess.API_ENV, "X-Run-Token");

    private RunTokenRules() {
    }

    static void apply(List<BlockDefinition> blocks, Map<String, Object> metadata, List<AnalyzerFinding> out) {
        if (RunAccess.requested(metadata)) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            String marker = BlockText.allStrings(block).stream()
                    .flatMap(text -> MARKERS.stream().filter(text::contains))
                    .findFirst().orElse(null);
            if (marker != null) {
                out.add(AnalyzerFinding.of("run-token-not-requested", Severity.MEDIUM, i, block.getName(),
                        "nutzt den Lauf-Token ('" + marker + "'), der Workflow fordert ihn aber nicht an — ohne"
                                + " metadata.permissions: [\"read\"] bekommt der Lauf keinen, der Aufruf scheitert mit 401",
                        "uses the run token ('" + marker + "'), but the workflow does not ask for one — without"
                                + " metadata.permissions: [\"read\"] the run gets none and the call fails with 401"));
            }
        }
    }
}
