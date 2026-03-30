package com.automation.service.trigger;

import com.automation.dto.request.CreateCompletionTriggerRequest;
import com.automation.entity.CompletionTrigger;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.CompletionTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompletionTriggerService {

    private final CompletionTriggerRepository repository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutor workflowExecutor;
    private final com.automation.service.workflow.WorkflowService workflowService;

    @Transactional(readOnly = true)
    public List<CompletionTrigger> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public CompletionTrigger findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CompletionTrigger", id));
    }

    @Transactional(readOnly = true)
    public List<CompletionTrigger> findBySourceWorkflowId(String workflowId) {
        return repository.findBySourceWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public List<CompletionTrigger> findByTargetWorkflowId(String workflowId) {
        return repository.findByTargetWorkflowId(workflowId);
    }

    @Transactional
    public CompletionTrigger create(CreateCompletionTriggerRequest req) {
        Workflow source = workflowRepository.findById(req.sourceWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", req.sourceWorkflowId()));
        Workflow target = workflowRepository.findById(req.targetWorkflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", req.targetWorkflowId()));

        validateNoCycle(req.sourceWorkflowId(), req.targetWorkflowId());

        CompletionTrigger trigger = CompletionTrigger.builder()
                .sourceWorkflow(source)
                .targetWorkflow(target)
                .triggerOnSuccess(req.triggerOnSuccess())
                .triggerOnFailure(req.triggerOnFailure())
                .triggerAlways(req.triggerAlways())
                .description(req.description())
                .contextVariables(req.contextVariables() != null ? new HashMap<>(req.contextVariables()) : new HashMap<>())
                .build();

        return repository.save(trigger);
    }

    @Transactional
    public CompletionTrigger enable(String id) {
        CompletionTrigger trigger = findById(id);
        // Re-validate cycle in case other triggers were added while this one was disabled
        validateNoCycle(trigger.getSourceWorkflow().getId(), trigger.getTargetWorkflow().getId());
        trigger.setEnabled(true);
        return repository.save(trigger);
    }

    @Transactional
    public CompletionTrigger disable(String id) {
        CompletionTrigger trigger = findById(id);
        trigger.setEnabled(false);
        return repository.save(trigger);
    }

    @Transactional
    public void delete(String id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("CompletionTrigger", id);
        }
        repository.deleteById(id);
    }

    @Async
    @EventListener
    @Transactional
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        List<CompletionTrigger> candidates =
                repository.findBySourceWorkflowIdAndEnabledTrue(event.getWorkflowId());

        for (CompletionTrigger trigger : candidates) {
            if (!shouldFire(trigger, event.getStatus())) {
                continue;
            }

            Map<String, Object> context = new HashMap<>(trigger.getContextVariables());
            context.put("completion_trigger_source_run_id", event.getRunId());
            context.put("completion_trigger_source_workflow_id", event.getWorkflowId());
            context.put("completion_trigger_source_workflow_name", event.getWorkflowName());
            context.put("completion_trigger_source_status", event.getStatus().name());

            try {
                // Load target workflow with eagerly initialized activatedSkills —
                // trigger.getTargetWorkflow() comes from an EAGER relationship but
                // leaves the LAZY ManyToMany uninitialized.
                Workflow targetWorkflow = workflowService.findByIdEager(trigger.getTargetWorkflow().getId());
                workflowExecutor.execute(targetWorkflow, context);
                trigger.setLastTriggeredAt(Instant.now());
                trigger.setTriggerCount(trigger.getTriggerCount() + 1);
                repository.save(trigger);
                log.info("CompletionTrigger fired: {} → {} (status={})",
                        event.getWorkflowId(), targetWorkflow.getId(), event.getStatus());
            } catch (Exception e) {
                log.error("CompletionTrigger failed to start target workflow {}: {}",
                        trigger.getTargetWorkflow().getId(), e.getMessage());
            }
        }
    }

    private boolean shouldFire(CompletionTrigger trigger, RunStatus status) {
        if (trigger.isTriggerAlways()) return true;
        return switch (status) {
            case COMPLETED, COMPLETED_WITH_ERRORS -> trigger.isTriggerOnSuccess();
            case FAILED -> trigger.isTriggerOnFailure();
            default -> false; // CANCELLED, RUNNING, PENDING, PAUSED — never fire
        };
    }

    private void validateNoCycle(String sourceId, String targetId) {
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("A workflow cannot trigger itself");
        }
        Set<String> visited = new HashSet<>();
        if (isReachable(targetId, sourceId, visited)) {
            throw new IllegalArgumentException(
                    "Adding this trigger would create a circular chain: " + targetId + " → ... → " + sourceId);
        }
    }

    private boolean isReachable(String fromId, String goalId, Set<String> visited) {
        if (!visited.add(fromId)) return false;
        List<CompletionTrigger> outgoing = repository.findBySourceWorkflowId(fromId);
        for (CompletionTrigger t : outgoing) {
            String nextId = t.getTargetWorkflow().getId();
            if (nextId.equals(goalId)) return true;
            if (isReachable(nextId, goalId, visited)) return true;
        }
        return false;
    }
}
