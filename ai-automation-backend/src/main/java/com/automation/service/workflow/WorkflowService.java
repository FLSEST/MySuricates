package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Reads workflows and writes their definitions: every create, update, restore, delete and skill change
 * is saved here, recorded through {@link WorkflowMutationGateway} and announced. Which request field
 * goes where, and how it is checked, is {@link WorkflowRequestFields}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final SkillRepository skillRepository;
    private final ActivityLogService activityLogService;
    private final WorkflowMutationGateway mutations;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<Workflow> findAll() {
        return workflowRepository.findAllWithActiveProject();
    }

    @Transactional(readOnly = true)
    public List<Workflow> findByProjectId(String projectId) {
        return workflowRepository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Workflow findById(String id) {
        return workflowRepository.findByIdWithSkills(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id));
    }

    @Transactional(readOnly = true)
    public Workflow findByIdEager(String id) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id));
        // Force-initialize lazy collections while still in transaction
        workflow.getBlocks().size();
        workflow.getActivatedSkills().size();
        return workflow;
    }

    @Transactional
    public Workflow create(CreateWorkflowRequest request, ChangeSource source) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));
        Workflow workflow = WorkflowRequestFields.newWorkflow(project, request);

        // Activate skills if provided
        if (request.activatedSkillIds() != null) {
            List<Skill> skills = skillRepository.findAllById(request.activatedSkillIds());
            workflow.setActivatedSkills(skills);
        }

        Workflow saved = workflowRepository.save(workflow);
        mutations.record(saved, null, source, null);
        log.info("Created workflow: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow created: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
        eventPublisher.publishEvent(new WorkflowContentChangedEvent(saved.getId()));
        eventPublisher.publishEvent(new ProjectWorkflowsChangedEvent(project.getId()));
        return saved;
    }

    @Transactional
    public Workflow update(String id, UpdateWorkflowRequest request, ChangeSource source) {
        Workflow workflow = findById(id);
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);

        boolean contentChanged = WorkflowRequestFields.apply(workflow, request);

        Workflow saved = workflowRepository.save(workflow);
        mutations.record(saved, before, source, request.changeMessage());
        log.info("Updated workflow: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow updated: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
        if (contentChanged) {
            eventPublisher.publishEvent(new WorkflowContentChangedEvent(saved.getId()));
            if (saved.getProject() != null) {
                eventPublisher.publishEvent(new ProjectWorkflowsChangedEvent(saved.getProject().getId()));
            }
        }
        return saved;
    }

    @Transactional
    public Workflow restoreFromSnapshot(String id, WorkflowSnapshot snapshot, String changeMessage) {
        Workflow workflow = findById(id);
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);

        WorkflowRequestFields.restore(workflow, snapshot);

        Workflow saved = workflowRepository.save(workflow);
        mutations.record(saved, before, ChangeSource.RESTORE, changeMessage);
        log.info("Restored workflow: {} ({}) from snapshot", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow restored: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Workflow> findByNameAndProjectId(String name, String projectId) {
        Optional<Workflow> result = workflowRepository.findByNameAndProjectId(name, projectId);
        result.ifPresent(w -> w.getActivatedSkills().size());
        return result;
    }

    @Transactional
    public void delete(String id) {
        Workflow workflow = findById(id);
        mutations.requireUnsealed(workflow, "deleting it");
        workflow.setDeletedAt(java.time.Instant.now());
        workflowRepository.save(workflow);
        log.info("Soft-deleted workflow: {} ({})", workflow.getName(), workflow.getId());
        if (workflow.getProject() != null) {
            eventPublisher.publishEvent(new ProjectWorkflowsChangedEvent(workflow.getProject().getId()));
        }
        activityLogService.warn(LogCategory.SYSTEM, "Workflow deleted: " + workflow.getName(), "WorkflowService", workflow.getId(), workflow.getId());
    }

    @Transactional
    public Workflow activateSkills(String workflowId, List<String> skillIds) {
        Workflow workflow = findById(workflowId);
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        List<Skill> skills = skillRepository.findAllById(skillIds);

        // Avoid duplicates
        var existingIds = workflow.getActivatedSkills().stream()
                .map(Skill::getId)
                .collect(java.util.stream.Collectors.toSet());
        skills.stream()
                .filter(s -> !existingIds.contains(s.getId()))
                .forEach(workflow.getActivatedSkills()::add);

        Workflow saved = workflowRepository.save(workflow);
        mutations.record(saved, before, ChangeSource.USER, null);
        return saved;
    }

    @Transactional
    public Workflow deactivateSkill(String workflowId, String skillId) {
        Workflow workflow = findById(workflowId);
        WorkflowSnapshot before = WorkflowSnapshot.from(workflow);
        workflow.getActivatedSkills().removeIf(s -> s.getId().equals(skillId));
        Workflow saved = workflowRepository.save(workflow);
        mutations.record(saved, before, ChangeSource.USER, null);
        return saved;
    }
}
