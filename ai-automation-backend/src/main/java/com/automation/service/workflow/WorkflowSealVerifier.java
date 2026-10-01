package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.exception.ErrorMessage;
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
     * @param refusal       the same reason as {@link RunAdmission}'s refusal, which the UI translates
     *                      (TODO 2b73ff48); {@code null} when the seal holds
     * @param refusalParams its params without the workflow {@code name}, which the caller adds
     */
    public record Verdict(Problem problem, String actualHash, Set<String> changedSkills, boolean skillsPinned,
                          Map<String, String> pins, String detail, boolean signed,
                          ErrorMessage refusal, Map<String, String> refusalParams) {
        public boolean intact() {
            return problem == Problem.NONE;
        }
    }

    /** Every {@code SEAL_*} refusal starts with this; {@link Verdict#detail()} is the rest of it. */
    static final String REFUSAL_PREFIX = "Sealed workflow '{name}' was not started: ";

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
                    ErrorMessage.SEAL_DEFINITION_UNVERIFIABLE, Map.of("error", String.valueOf(e.getMessage())));
        }
        if (!actual.equals(workflow.getSealedHash())) {
            return broken(Problem.DEFINITION_CHANGED, actual, Set.of(), skillsPinned,
                    ErrorMessage.SEAL_DEFINITION_CHANGED, Map.of());
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
                    ErrorMessage.SEAL_SKILLS_UNVERIFIABLE, Map.of("error", String.valueOf(e.getMessage())));
        }
        if (!changed.isEmpty()) {
            return broken(Problem.SKILLS_CHANGED, actual, changed, true,
                    ErrorMessage.SEAL_SKILLS_CHANGED, Map.of("skills", String.join(", ", changed)));
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
                return refused(Problem.SIGNATURE_INVALID, actualHash, Set.of(), skillsPinned, true,
                        ErrorMessage.SEAL_SIGNATURE_INVALID, Map.of());
            }
            return new Verdict(Problem.NONE, actualHash, Set.of(), skillsPinned, pins, null, true, null, Map.of());
        }
        if (modules.isEnabled(ModuleCatalog.REQUIRE_SIGNED_SEAL)) {
            return refused(Problem.SIGNATURE_MISSING, actualHash, Set.of(), skillsPinned, false,
                    ErrorMessage.SEAL_SIGNATURE_MISSING, Map.of());
        }
        return new Verdict(Problem.NONE, actualHash, Set.of(), skillsPinned, pins, null, false, null, Map.of());
    }

    private static Verdict broken(Problem problem, String actualHash, Set<String> changed, boolean skillsPinned,
                                  ErrorMessage refusal, Map<String, String> params) {
        return refused(problem, actualHash, changed, skillsPinned, false, refusal, params);
    }

    /** The detail is the refusal text after {@link #REFUSAL_PREFIX} — one wording for both. */
    private static Verdict refused(Problem problem, String actualHash, Set<String> changed, boolean skillsPinned,
                                   boolean signed, ErrorMessage refusal, Map<String, String> params) {
        String detail = refusal.format(params).substring(REFUSAL_PREFIX.length());
        return new Verdict(problem, actualHash, changed, skillsPinned, null, detail, signed, refusal, params);
    }
}
