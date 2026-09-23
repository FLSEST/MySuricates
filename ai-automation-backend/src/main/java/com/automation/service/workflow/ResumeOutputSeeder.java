package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rebuilds named block outputs ({{ block.x.output }}) and the compression source index from the
 * persisted steps before a resumed segment runs (TODO f9384e48). Taint is not touched here — it is
 * re-applied from the exported names by {@code ExecutionContextFactory}.
 */
final class ResumeOutputSeeder {

    private static final Set<StepStatus> FINISHED = Set.of(StepStatus.COMPLETED, StepStatus.FAILED, StepStatus.SKIPPED);

    private ResumeOutputSeeder() {
    }

    /**
     * Seeds the latest attempt of every skipped block. {@code blocksToSkip} counts blocks, not step
     * indices — see {@link ResumePlan}. An earlier, interrupted attempt of the same block is left out:
     * recording one block name twice would mark it ambiguous in the source index (TODO cd0ea0f9).
     */
    static void seed(ExecutionContext context, List<StepResult> persistedSteps, int blocksToSkip) {
        List<StepResult> ordered = ResumePlan.inIndexOrder(persistedSteps);
        List<Integer> blocks = ResumePlan.blockPositions(ordered);
        Map<Integer, StepResult> latestAttempt = new TreeMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (blocks.get(i) < blocksToSkip) {
                latestAttempt.put(blocks.get(i), ordered.get(i));
            }
        }
        latestAttempt.values().stream()
                .filter(s -> FINISHED.contains(s.getStatus()))
                .forEach(s -> {
                    String name = s.getMetadata() != null && s.getMetadata().get("blockName") instanceof String n
                            && !n.isBlank() ? n : null;
                    context.setBlockOutput(name, s.getOutput());
                    context.getSourceSteps().record(name, s);
                });
    }
}
