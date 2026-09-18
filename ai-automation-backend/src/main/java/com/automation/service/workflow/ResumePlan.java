package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;

import java.util.Comparator;
import java.util.List;

/**
 * Where a resumed run picks up: how many leading blocks already ran and which step index the new
 * segment starts at.
 *
 * <p>The resumed segment is cut by block <em>position</em>, not by step index. A wrapper block
 * (foreach, while, retry, conditional, switch) consumes one extra step index per item or iteration,
 * so a block's step index is only ever ≥ its position in the block list, and the two drift apart as
 * soon as a wrapper has run. Comparing a freshly allocated index of the resumed segment against a
 * persisted index of the first segment therefore skipped blocks that had never run (TODO 1be423b4).
 *
 * <p>The mapping this relies on: {@link BlockStepRunner} persists exactly one step per top-level
 * block, in block order — the inner items of wrapper blocks are never persisted. So the position of
 * a persisted step in the index-ordered list is the position of its block.
 */
record ResumePlan(boolean resuming, int blocksToSkip, int firstStepIndex) {

    /** A run that starts at its first block. */
    static final ResumePlan FRESH = new ResumePlan(false, 0, 0);

    /**
     * Resumes after the last COMPLETED step. Steps of the new segment are numbered above everything
     * already persisted, so indices stay unique and ordered across segments.
     */
    static ResumePlan after(List<StepResult> persistedSteps) {
        List<StepResult> ordered = persistedSteps.stream()
                .sorted(Comparator.comparingInt(StepResult::getStepIndex))
                .toList();
        int lastCompletedPosition = -1;
        int highestStepIndex = -1;
        for (int position = 0; position < ordered.size(); position++) {
            StepResult step = ordered.get(position);
            if (step.getStatus() == StepStatus.COMPLETED) {
                lastCompletedPosition = position;
            }
            highestStepIndex = Math.max(highestStepIndex, step.getStepIndex());
        }
        return new ResumePlan(true, lastCompletedPosition + 1, highestStepIndex + 1);
    }
}
