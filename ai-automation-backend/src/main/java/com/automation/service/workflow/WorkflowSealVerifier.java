package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
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
        /** The seal carries a signature that does not match (forged or moved seal). */
        SIGNATURE_INVALID,
        /** With {@code require-signed-seal}: the seal carries no signature at all. */
        SIGNATURE_MISSING,
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
                          Map<String, String> pins, String detail, boolean signed) {
        public boolean intact() {
            return problem == Problem.NONE;
        }
    }

    private final WorkflowDefinitionHasher hasher;
    private final WorkflowSkillPins skillPins;
    private final WorkflowSealSigner signer;
    private final ModuleRegistry modules;

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
            return withSignature(workflow, actual, null, false);
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
        return withSignature(workflow, actual, sealed, true);
    }

    /**
     * Last check: a signature that is there must match — always, like the seal itself. Whether a
     * missing signature is accepted is what {@code require-signed-seal} decides, so seals from
     * before signing keep working until that switch is turned on.
     */
    private Verdict withSignature(Workflow workflow, String actualHash, Map<String, String> pins, boolean skillsPinned) {
        String signature = workflow.getSealedSignature();
        if (signature != null) {
            if (!signer.verify(workflow.getId(), actualHash, pins, signature)) {
                return new Verdict(Problem.SIGNATURE_INVALID, actualHash, Set.of(), skillsPinned, null,
                        "its signature does not match — the seal was forged or copied from another workflow", true);
            }
            return new Verdict(Problem.NONE, actualHash, Set.of(), skillsPinned, pins, null, true);
        }
        if (modules.isEnabled(ModuleCatalog.REQUIRE_SIGNED_SEAL)) {
            return new Verdict(Problem.SIGNATURE_MISSING, actualHash, Set.of(), skillsPinned, null,
                    "it carries no signature (automation.hardening.require-signed-seal) — seal it again", false);
        }
        return new Verdict(Problem.NONE, actualHash, Set.of(), skillsPinned, pins, null, false);
    }

    private static Verdict broken(Problem problem, String actualHash, Set<String> changed, boolean skillsPinned,
                                  String detail) {
        return new Verdict(problem, actualHash, changed, skillsPinned, null, detail, false);
    }
}
