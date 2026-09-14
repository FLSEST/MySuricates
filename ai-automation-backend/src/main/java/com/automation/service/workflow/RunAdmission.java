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

/**
 * Single gate every workflow run passes before its run row exists (called by
 * {@link WorkflowExecutor}). Records provenance — the definition hash and the matching version —
 * and refuses runs that violate a hardening rule. A refused run leaves no run row behind.
 *
 * <p>Rules, in order: a sealed definition must match its seal (always); with
 * {@code automation.hardening.require-sealed-for-unattended} an unattended run — or a chain /
 * sub-workflow run whose origin is unattended or unknown — needs a sealed workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunAdmission {

    /** What the run row records about its origin. Fields are null when provenance is off. */
    public record Ticket(String definitionHash, String definitionVersionId) {
        static Ticket none() {
            return new Ticket(null, null);
        }
    }

    private final ModuleRegistry modules;
    private final WorkflowDefinitionHasher hasher;
    private final WorkflowVersionService versionService;
    private final ActivityLogService activityLog;

    public Ticket admit(Workflow workflow, TriggerSource source, Map<String, Object> inputContext) {
        // Seals are checked regardless of the switches: a seal must not lapse because a flag changed.
        String hash = workflow.isSealed() ? verifySeal(workflow, source) : null;
        if (!workflow.isSealed() && modules.isEnabled(ModuleCatalog.REQUIRE_SEALED_FOR_UNATTENDED)) {
            requireAttended(workflow, source, inputContext);
        }
        if (!modules.isEnabled(ModuleCatalog.PROVENANCE)) {
            return Ticket.none();
        }
        try {
            if (hash == null) {
                hash = hasher.hash(workflow);
            }
            String versionId = versionService.findVersionIdByHash(workflow.getId(), hash).orElse(null);
            return new Ticket(hash, versionId);
        } catch (RuntimeException e) {
            // Provenance is metadata: a definition that cannot be hashed (e.g. lazy data outside a
            // session) must not stop the run — the executor fails such runs on its own if needed.
            log.warn("Could not compute definition hash for workflow {} ({}): {}", workflow.getId(), source, e.getMessage());
            return Ticket.none();
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
        activityLog.log(LogLevel.WARN, LogCategory.SECURITY,
                "Run refused — unattended run (" + origin + ") of unsealed workflow '" + workflow.getName() + "'",
                "RunAdmission", workflow.getId(), metadata, workflow.getId());
        throw new HardeningException(ErrorCode.UNATTENDED_REQUIRES_SEAL,
                "Workflow '" + workflow.getName() + "' was not started: unattended runs (" + origin
                        + ") require a sealed workflow (automation.hardening.require-sealed-for-unattended)");
    }

    /** Returns the verified hash or refuses the run (fail closed: an unverifiable seal is a broken seal). */
    private String verifySeal(Workflow workflow, TriggerSource source) {
        String actual;
        try {
            actual = hasher.hash(workflow);
        } catch (RuntimeException e) {
            throw refuse(workflow, source, null, "its definition could not be verified (" + e.getMessage() + ")");
        }
        if (!actual.equals(workflow.getSealedHash())) {
            throw refuse(workflow, source, actual, "its definition no longer matches the seal");
        }
        return actual;
    }

    private HardeningException refuse(Workflow workflow, TriggerSource source, String actualHash, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("expectedHash", workflow.getSealedHash());
        metadata.put("actualHash", actualHash);
        metadata.put("triggerSource", source.name());
        activityLog.log(LogLevel.ERROR, LogCategory.SECURITY,
                "Run refused — sealed workflow '" + workflow.getName() + "': " + reason,
                "RunAdmission", workflow.getId(), metadata, workflow.getId());
        return new HardeningException(ErrorCode.WORKFLOW_TAMPERED,
                "Sealed workflow '" + workflow.getName() + "' was not started: " + reason);
    }
}
