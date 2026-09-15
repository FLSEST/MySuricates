package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds named block outputs ({{ block.x.output }}) and the compression source index from the
 * persisted steps before a resumed segment runs (TODO f9384e48). Taint is not touched here — it is
 * re-applied from the exported names by {@code ExecutionContextFactory}.
 */
final class ResumeOutputSeeder {

    private static final Set<StepStatus> FINISHED = Set.of(StepStatus.COMPLETED, StepStatus.FAILED, StepStatus.SKIPPED);

    private ResumeOutputSeeder() {
    }

    static void seed(ExecutionContext context, List<StepResult> persistedSteps, int skipToStepIndex) {
        persistedSteps.stream()
                .filter(s -> s.getStepIndex() < skipToStepIndex && FINISHED.contains(s.getStatus()))
                .sorted(Comparator.comparingInt(StepResult::getStepIndex))
                .forEach(s -> {
                    String name = s.getMetadata() != null && s.getMetadata().get("blockName") instanceof String n
                            && !n.isBlank() ? n : null;
                    context.setBlockOutput(name, s.getOutput());
                    context.getSourceSteps().record(name, s);
                });
    }
}
