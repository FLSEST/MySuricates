package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowSealEvent;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seals and unseals workflows (automation.hardening.sealing). Sealing pins the current definition
 * hash and the content hash of every skill the workflow uses ({@link WorkflowSkillPins});
 * {@link WorkflowMutationGateway} then rejects definition changes, {@code SkillMutationGateway}
 * rejects changes to those skills, and {@link RunAdmission} refuses runs whose definition or skills
 * no longer match. Sealing an already sealed workflow whose seal predates skill pinning adds the
 * pins, provided the definition still matches. All operations are logged as SECURITY events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSealService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowDefinitionHasher hasher;
    private final WorkflowSkillPins skillPins;
    private final WorkflowSealSigner signer;
    private final WorkflowSealHistory history;
    private final ModuleRegistry modules;
    private final ActivityLogService activityLog;

    @Transactional
    public Workflow seal(String workflowId) {
        Workflow workflow = load(workflowId);
        if (workflow.isSealed() && workflow.getSealedSkillHashes() != null) {
            return workflow;
        }
        String hash = hasher.hash(workflow);
        boolean legacySeal = workflow.isSealed();
        if (legacySeal && !hash.equals(workflow.getSealedHash())) {
            // Pinning skills re-baselines nothing else: a definition that no longer matches stays refused.
            throw new HardeningException(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow '" + workflow.getName()
                    + "' no longer matches its seal — unseal it, review the definition and seal it again");
        }
        if (!legacySeal) {
            workflow.setSealed(true);
            workflow.setSealedHash(hash);
            workflow.setSealedAt(Instant.now());
        }
        Map<String, String> pins = skillPins.pins(workflow);
        workflow.setSealedSkillHashes(WorkflowSkillPins.toJson(pins));
        String signature = signer.sign(workflow.getId(), hash, pins);
        workflow.setSealedSignature(signature);
        Workflow saved = workflowRepository.save(workflow);
        history.record(saved, legacySeal ? WorkflowSealEvent.Action.PINS_ADDED : WorkflowSealEvent.Action.SEALED,
                hash, pins, signature);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sealedHash", hash);
        metadata.put("skillHashes", pins);
        metadata.put("signed", signature != null);
        String title = legacySeal ? "Workflow seal extended to its skills: " : "Workflow sealed: ";
        activityLog.log(LogLevel.WARN, LogCategory.SECURITY, title + saved.getName(),
                "WorkflowSealService", saved.getId(), metadata, saved.getId());
        log.info("Sealed workflow {} ({}) at {} with {} skill pin(s)", saved.getName(), saved.getId(), hash, pins.size());
        return saved;
    }

    @Transactional
    public Workflow unseal(String workflowId) {
        Workflow workflow = load(workflowId);
        if (!workflow.isSealed()) {
            return workflow;
        }
        if (modules.isEnabled(ModuleCatalog.SEAL_IMMUTABLE)) {
            // Deliberately no REST path around this: the switch is startup-only, so a leaked API key
            // (or an agent holding it) cannot release a seal — only someone with the configuration can.
            activityLog.logRefusal(LogLevel.WARN, LogCategory.SECURITY,
                    "Unseal refused — sealing is immutable: " + workflow.getName(),
                    "WorkflowSealService", workflow.getId(), Map.of(), workflow.getId());
            throw new HardeningException(ErrorCode.SEAL_IMMUTABLE, "Workflow '" + workflow.getName()
                    + "' cannot be unsealed: automation.hardening.seal-immutable is on (startup configuration)");
        }
        String previousHash = workflow.getSealedHash();
        workflow.setSealed(false);
        workflow.setSealedHash(null);
        workflow.setSealedAt(null);
        workflow.setSealedSkillHashes(null);
        workflow.setSealedSignature(null);
        Workflow saved = workflowRepository.save(workflow);
        history.record(saved, WorkflowSealEvent.Action.UNSEALED, previousHash, null, null);
        activityLog.log(LogLevel.WARN, LogCategory.SECURITY, "Workflow unsealed: " + saved.getName(),
                "WorkflowSealService", saved.getId(), Map.of("previousSealedHash", previousHash), saved.getId());
        log.info("Unsealed workflow {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    private Workflow load(String workflowId) {
        return workflowRepository.findByIdWithSkills(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));
    }
}
