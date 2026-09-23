package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;

import java.util.ArrayList;
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
 * <p>A persisted step names its block in {@code metadata.blockIndex} (written by
 * {@link BlockStepRunner#startStep}). The position of a step in the index-ordered list is no
 * substitute once a run has been resumed: the interrupted attempt of a block stays persisted next to
 * its new one, so a second resume would count the stale attempt as a passed block and skip the next
 * one (TODO cd0ea0f9). Only runs whose steps predate {@code blockIndex} fall back to the position.
 */
record ResumePlan(boolean resuming, int blocksToSkip, int firstStepIndex) {

    /** A run that starts at its first block. */
    static final ResumePlan FRESH = new ResumePlan(false, 0, 0);

    /**
     * Resumes after the block of the last COMPLETED step. Steps of the new segment are numbered above
     * everything already persisted, so indices stay unique and ordered across segments.
     */
    static ResumePlan after(List<StepResult> persistedSteps) {
        List<StepResult> ordered = inIndexOrder(persistedSteps);
        List<Integer> blocks = blockPositions(ordered);
        int lastCompletedBlock = -1;
        int highestStepIndex = -1;
        for (int i = 0; i < ordered.size(); i++) {
            StepResult step = ordered.get(i);
            if (step.getStatus() == StepStatus.COMPLETED) {
                lastCompletedBlock = Math.max(lastCompletedBlock, blocks.get(i));
            }
            highestStepIndex = Math.max(highestStepIndex, step.getStepIndex());
        }
        return new ResumePlan(true, lastCompletedBlock + 1, highestStepIndex + 1);
    }

    static List<StepResult> inIndexOrder(List<StepResult> persistedSteps) {
        return persistedSteps.stream()
                .sorted(Comparator.comparingInt(StepResult::getStepIndex))
                .toList();
    }

    /**
     * The top-level block of each step in {@code ordered} (same order): {@code metadata.blockIndex}
     * when every step carries it, otherwise the step's position — the mapping that held before a run
     * could be resumed more than once.
     */
    static List<Integer> blockPositions(List<StepResult> ordered) {
        List<Integer> indexed = new ArrayList<>(ordered.size());
        for (StepResult step : ordered) {
            Object value = step.getMetadata() != null ? step.getMetadata().get("blockIndex") : null;
            if (!(value instanceof Number number)) {
                List<Integer> byPosition = new ArrayList<>(ordered.size());
                for (int position = 0; position < ordered.size(); position++) {
                    byPosition.add(position);
                }
                return byPosition;
            }
            indexed.add(number.intValue());
        }
        return indexed;
    }
}
