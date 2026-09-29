package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/**
 * Single gate every workflow run passes before its run row exists (called by
 * {@link WorkflowExecutor}). Records provenance — the definition hash and the matching version —
 * and refuses runs that violate a hardening rule. A refused run leaves no run row behind.
 *
 * <p>Rules, in order: a sealed definition must match its seal, and so must the content of every
 * skill it uses (always); with
 * {@code automation.hardening.require-sealed-for-unattended} an unattended run — or a chain /
 * sub-workflow run whose origin is unattended or unknown — needs a sealed workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunAdmission {

    /**
     * What the run row records about its origin. Hash and version are null when provenance is off;
     * the seal state is recorded regardless — it is never derived later from the workflow's current
     * state. {@code sealedSkillPins} are the skill hashes verified at start (JSON), if the seal pins any.
     */
    public record Ticket(String definitionHash, String definitionVersionId, Boolean sealed, String sealedSkillPins) {
        public Ticket(String definitionHash, String definitionVersionId) {
            this(definitionHash, definitionVersionId, null, null);
        }
    }

    private final ModuleRegistry modules;
    private final WorkflowDefinitionHasher hasher;
    private final WorkflowSealVerifier sealVerifier;
    private final WorkflowVersionService versionService;
    private final ActivityLogService activityLog;

    public Ticket admit(Workflow workflow, TriggerSource source, Map<String, Object> inputContext) {
        // Seals are checked regardless of the switches: a seal must not lapse because a flag changed.
        WorkflowSealVerifier.Verdict verdict = workflow.isSealed() ? verifySeal(workflow, source) : null;
        if (!workflow.isSealed() && modules.isEnabled(ModuleCatalog.REQUIRE_SEALED_FOR_UNATTENDED)) {
            requireAttended(workflow, source, inputContext);
        }
        Boolean sealed = workflow.isSealed();
        String pins = verdict != null && verdict.pins() != null ? WorkflowSkillPins.toJson(verdict.pins()) : null;
        if (!modules.isEnabled(ModuleCatalog.PROVENANCE)) {
            return new Ticket(null, null, sealed, pins);
        }
        try {
            String hash = verdict != null ? verdict.actualHash() : hasher.hash(workflow);
            String versionId = versionService.findVersionIdByHash(workflow.getId(), hash).orElse(null);
            return new Ticket(hash, versionId, sealed, pins);
        } catch (RuntimeException e) {
            // Provenance is metadata: a definition that cannot be hashed (e.g. lazy data outside a
            // session) must not stop the run — the executor fails such runs on its own if needed.
            log.warn("Could not compute definition hash for workflow {} ({}): {}", workflow.getId(), source, e.getMessage());
            return new Ticket(null, null, sealed, pins);
        }
    }

    private void requireAttended(Workflow workflow, TriggerSource source, Map<String, Object> inputContext) {
        TriggerSource effective = source.effective(inputContext);
        if (effective != null && !effective.unattended()) {
            return;
        }
        String origin = effective != null ? effective.name() : "unknown";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("triggerSource", source.name());
        metadata.put("effectiveSource", origin);
        activityLog.logRefusal(LogLevel.WARN, LogCategory.SECURITY,
                "Run refused — unattended run (" + origin + ") of unsealed workflow '" + workflow.getName() + "'",
                "RunAdmission", workflow.getId(), metadata, workflow.getId());
        throw new HardeningException(ErrorCode.UNATTENDED_REQUIRES_SEAL,
                "Workflow '" + workflow.getName() + "' was not started: unattended runs (" + origin
                        + ") require a sealed workflow (automation.hardening.require-sealed-for-unattended)",
                Map.of("name", String.valueOf(workflow.getName()), "origin", origin));
    }

    /** Returns the verdict of an intact seal or refuses the run (fail closed: an unverifiable seal is a broken seal). */
    private WorkflowSealVerifier.Verdict verifySeal(Workflow workflow, TriggerSource source) {
        WorkflowSealVerifier.Verdict verdict = sealVerifier.verify(workflow);
        if (!verdict.intact()) {
            throw refuse(workflow, source, verdict);
        }
        if (!verdict.skillsPinned()) {
            log.warn("Sealed workflow {} ({}) predates skill pinning — seal it again to pin its skills",
                    workflow.getName(), workflow.getId());
        }
        return verdict;
    }

    private HardeningException refuse(Workflow workflow, TriggerSource source, WorkflowSealVerifier.Verdict verdict) {
        String reason = verdict.detail();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("expectedHash", workflow.getSealedHash());
        metadata.put("actualHash", verdict.actualHash());
        metadata.put("triggerSource", source.name());
        metadata.put("problem", verdict.problem().name());
        if (!verdict.changedSkills().isEmpty()) {
            metadata.put("changedSkills", List.copyOf(verdict.changedSkills()));
        }
        activityLog.logRefusal(LogLevel.ERROR, LogCategory.SECURITY,
                "Run refused — sealed workflow '" + workflow.getName() + "': " + reason,
                "RunAdmission", workflow.getId(), metadata, workflow.getId());
        return new HardeningException(ErrorCode.WORKFLOW_TAMPERED,
                "Sealed workflow '" + workflow.getName() + "' was not started: " + reason);
    }

}
