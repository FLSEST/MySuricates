package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;

import java.util.Map;

/**
 * What started a run, as the insights' trigger distribution and automation level count it.
 *
 * <p>Read from {@link WorkflowRun#getTriggerSource()}. Before that column existed the only clues
 * were the schedule id and the {@code todoTriggerRun} marker, so every webhook, chain or wish run
 * counted as manual and the webhook counter stayed at 0 (d75fbfef) — runs without a trigger source
 * still fall back to those clues.
 */
public enum TriggerCategory {
    MANUAL, SCHEDULED, WEBHOOK, COMPLETION_TRIGGER, TODO_TRIGGER,
    /** Started by the platform in another way: chain, sub-workflow, wish, auto-repair, auto-resume. */
    OTHER_AUTOMATIC;

    public static TriggerCategory of(WorkflowRun run) {
        TriggerSource source = run.getTriggerSource();
        if (source == null) {
            if (run.getTriggeredByScheduleId() != null) return SCHEDULED;
            boolean todoTriggerRun = run.getMetadata() != null && Boolean.TRUE.equals(run.getMetadata().get("todoTriggerRun"));
            return todoTriggerRun ? TODO_TRIGGER : MANUAL;
        }
        return switch (source) {
            case MANUAL, RETRY, RESUME, TODO_RESOLUTION, ASSISTANT -> MANUAL;
            case SCHEDULE -> SCHEDULED;
            case WEBHOOK -> WEBHOOK;
            case COMPLETION_TRIGGER -> COMPLETION_TRIGGER;
            case TODO_TRIGGER -> TODO_TRIGGER;
            case AUTO_REPAIR, AUTO_RESUME, WISH, CHAIN, SUB_WORKFLOW -> OTHER_AUTOMATIC;
        };
    }

    public static DashboardResponse.TriggerDistribution distribution(Map<TriggerCategory, long[]> counts) {
        return new DashboardResponse.TriggerDistribution(
                count(counts, MANUAL), count(counts, SCHEDULED), count(counts, WEBHOOK),
                count(counts, COMPLETION_TRIGGER), count(counts, TODO_TRIGGER), count(counts, OTHER_AUTOMATIC));
    }

    private static long count(Map<TriggerCategory, long[]> counts, TriggerCategory category) {
        long[] value = counts.get(category);
        return value == null ? 0 : value[0];
    }
}
