package com.automation.service.workflow;

import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Single gate every workflow run passes before its run row exists (called by
 * {@link WorkflowExecutor}). Records provenance — the definition hash and the matching version —
 * and is where the optional hardening policies (sealing, unattended runs) refuse runs.
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

    public Ticket admit(Workflow workflow, TriggerSource source, Map<String, Object> inputContext) {
        if (!modules.isEnabled(ModuleCatalog.PROVENANCE)) {
            return Ticket.none();
        }
        try {
            String hash = hasher.hash(workflow);
            String versionId = versionService.findVersionIdByHash(workflow.getId(), hash).orElse(null);
            return new Ticket(hash, versionId);
        } catch (RuntimeException e) {
            // Provenance is metadata: a definition that cannot be hashed (e.g. lazy data outside a
            // session) must not stop the run — the executor fails such runs on its own if needed.
            log.warn("Could not compute definition hash for workflow {} ({}): {}", workflow.getId(), source, e.getMessage());
            return Ticket.none();
        }
    }
}
