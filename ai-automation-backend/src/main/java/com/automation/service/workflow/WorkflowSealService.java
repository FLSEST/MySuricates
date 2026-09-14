package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Seals and unseals workflows (automation.hardening.sealing). Sealing pins the current definition
 * hash; {@link WorkflowMutationGateway} then rejects changes and {@link RunAdmission} refuses runs
 * whose definition no longer matches. Both operations are logged as SECURITY events.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowSealService {

    private final WorkflowRepository workflowRepository;
    private final WorkflowDefinitionHasher hasher;
    private final ActivityLogService activityLog;

    @Transactional
    public Workflow seal(String workflowId) {
        Workflow workflow = load(workflowId);
        if (workflow.isSealed()) {
            return workflow;
        }
        String hash = hasher.hash(workflow);
        workflow.setSealed(true);
        workflow.setSealedHash(hash);
        workflow.setSealedAt(Instant.now());
        Workflow saved = workflowRepository.save(workflow);
        activityLog.log(LogLevel.WARN, LogCategory.SECURITY, "Workflow sealed: " + saved.getName(),
                "WorkflowSealService", saved.getId(), Map.of("sealedHash", hash), saved.getId());
        log.info("Sealed workflow {} ({}) at {}", saved.getName(), saved.getId(), hash);
        return saved;
    }

    @Transactional
    public Workflow unseal(String workflowId) {
        Workflow workflow = load(workflowId);
        if (!workflow.isSealed()) {
            return workflow;
        }
        String previousHash = workflow.getSealedHash();
        workflow.setSealed(false);
        workflow.setSealedHash(null);
        workflow.setSealedAt(null);
        Workflow saved = workflowRepository.save(workflow);
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
