package com.automation.service.workflow;

import com.automation.entity.Workflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Checks a sealed workflow against its seal: the definition hash and — for seals that pin them —
 * the content of every skill it uses. One implementation for both {@link RunAdmission}, which
 * refuses the run on a broken seal, and the seal status the UI shows without starting a run, so the
 * two can never disagree.
 */
@Component
@RequiredArgsConstructor
public class WorkflowSealVerifier {

    public enum Problem {
        /** Seal holds. */
        NONE,
        /** The definition hash no longer matches {@code sealed_hash}. */
        DEFINITION_CHANGED,
        /** A pinned skill was changed, added or deleted. */
        SKILLS_CHANGED,
        /** The definition or the skills could not be hashed — treated as broken (fail closed). */
        UNVERIFIABLE
    }

    /**
     * @param actualHash    current definition hash, {@code null} if it could not be computed
     * @param changedSkills skill IDs whose pin differs (only for {@link Problem#SKILLS_CHANGED})
     * @param skillsPinned  whether the seal pins skills at all ({@code false} for seals made before V80)
     * @param pins          the verified pins when the seal holds and pins skills, else {@code null}
     * @param detail        human-readable reason for a broken seal, {@code null} when it holds
     */
    public record Verdict(Problem problem, String actualHash, Set<String> changedSkills, boolean skillsPinned,
                          Map<String, String> pins, String detail) {
        public boolean intact() {
            return problem == Problem.NONE;
        }
    }

    private final WorkflowDefinitionHasher hasher;
    private final WorkflowSkillPins skillPins;

    /** Only meaningful for sealed workflows; the caller checks {@link Workflow#isSealed()}. */
    public Verdict verify(Workflow workflow) {
        boolean skillsPinned = workflow.getSealedSkillHashes() != null;
        String actual;
        try {
            actual = hasher.hash(workflow);
        } catch (RuntimeException e) {
            return broken(Problem.UNVERIFIABLE, null, Set.of(), skillsPinned,
                    "its definition could not be verified (" + e.getMessage() + ")");
        }
        if (!actual.equals(workflow.getSealedHash())) {
            return broken(Problem.DEFINITION_CHANGED, actual, Set.of(), skillsPinned,
                    "its definition no longer matches the seal");
        }
        if (!skillsPinned) {
            return new Verdict(Problem.NONE, actual, Set.of(), false, null, null);
        }
        Map<String, String> sealed;
        Set<String> changed;
        try {
            sealed = WorkflowSkillPins.fromJson(workflow.getSealedSkillHashes());
            changed = WorkflowSkillPins.changed(sealed, skillPins.pins(workflow));
        } catch (RuntimeException e) {
            return broken(Problem.UNVERIFIABLE, actual, Set.of(), true,
                    "its skills could not be verified (" + e.getMessage() + ")");
        }
        if (!changed.isEmpty()) {
            return broken(Problem.SKILLS_CHANGED, actual, changed, true,
                    "skill(s) " + String.join(", ", changed) + " changed since sealing — review them and seal again");
        }
        return new Verdict(Problem.NONE, actual, Set.of(), true, sealed, null);
    }

    private static Verdict broken(Problem problem, String actualHash, Set<String> changed, boolean skillsPinned,
                                  String detail) {
        return new Verdict(problem, actualHash, changed, skillsPinned, null, detail);
    }
}
