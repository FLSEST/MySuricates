package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Seal state of a workflow as the UI shows it, checked without starting a run (same check as
 * RunAdmission). Listed for every sealed workflow and for every workflow with refused starts in the
 * last seven days — a refused start leaves no run row, so this is where it becomes visible.
 *
 * @param state           {@code INTACT}, {@code VIOLATED} or {@code NOT_SEALED}
 * @param problem         {@code NONE}, {@code DEFINITION_CHANGED}, {@code SKILLS_CHANGED} or {@code UNVERIFIABLE}
 * @param skillsPinned    whether the seal covers skill content ({@code false} for seals from before V80)
 * @param signed          whether the seal carries a signature (key outside the database)
 * @param refusedStarts   starts refused by RunAdmission in the last seven days
 * @param lastRefusal     activity-log message of the latest refusal, {@code null} if none
 */
public record WorkflowSealStatusResponse(
        String workflowId,
        String workflowName,
        String state,
        String problem,
        String detail,
        List<SkillRef> changedSkills,
        boolean skillsPinned,
        boolean signed,
        int pinnedSkillCount,
        Instant sealedAt,
        int refusedStarts,
        Instant lastRefusedAt,
        String lastRefusal
) {
    /** A skill named in the status; {@code name} is null when the skill no longer exists. */
    public record SkillRef(String id, String name) {}
}
