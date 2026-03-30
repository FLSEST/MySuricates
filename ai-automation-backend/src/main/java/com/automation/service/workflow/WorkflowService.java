package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final SkillRepository skillRepository;
    private final ActivityLogService activityLogService;

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
    public Workflow create(CreateWorkflowRequest request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));

        Workflow workflow = Workflow.builder()
                .project(project)
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .blocks(request.blocks() != null ? request.blocks() : new ArrayList<>())
                .instructions(request.instructions())
                .model(request.model())
                .provider(request.provider())
                .templateId(request.templateId())
                .build();

        if (request.version() != null && !request.version().isBlank()) {
            workflow.setVersion(request.version());
        }

        if (request.metadata() != null) {
            workflow.getMetadata().putAll(request.metadata());
        }

        if (request.defaultContext() != null) {
            workflow.getDefaultContext().putAll(request.defaultContext());
        }

        // Activate skills if provided
        if (request.activatedSkillIds() != null) {
            List<Skill> skills = skillRepository.findAllById(request.activatedSkillIds());
            workflow.setActivatedSkills(skills);
        }

        Workflow saved = workflowRepository.save(workflow);
        log.info("Created workflow: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow created: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
        return saved;
    }

    @Transactional
    public Workflow update(String id, UpdateWorkflowRequest request) {
        Workflow workflow = findById(id);

        if (request.name() != null) {
            workflow.setName(request.name());
        }
        if (request.description() != null) {
            workflow.setDescription(request.description());
        }
        if (request.icon() != null) {
            workflow.setIcon(request.icon());
        }
        if (request.blocks() != null) {
            workflow.setBlocks(request.blocks());
        }
        if (request.instructions() != null) {
            workflow.setInstructions(request.instructions());
        }
        if (request.model() != null) {
            workflow.setModel(request.model());
        }
        if (request.provider() != null) {
            workflow.setProvider(request.provider());
        }
        if (request.metadata() != null) {
            Map<String, Object> merged = new HashMap<>(workflow.getMetadata());
            merged.putAll(request.metadata());
            workflow.setMetadata(merged);
        }
        if (request.defaultContext() != null) {
            workflow.setDefaultContext(new HashMap<>(request.defaultContext()));
        }

        Workflow saved = workflowRepository.save(workflow);
        log.info("Updated workflow: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow updated: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
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
        workflow.setDeletedAt(java.time.Instant.now());
        workflowRepository.save(workflow);
        log.info("Soft-deleted workflow: {} ({})", workflow.getName(), workflow.getId());
        activityLogService.warn(LogCategory.SYSTEM, "Workflow deleted: " + workflow.getName(), "WorkflowService", workflow.getId(), workflow.getId());
    }

    @Transactional
    public Workflow activateSkills(String workflowId, List<String> skillIds) {
        Workflow workflow = findById(workflowId);
        List<Skill> skills = skillRepository.findAllById(skillIds);

        // Avoid duplicates
        var existingIds = workflow.getActivatedSkills().stream()
                .map(Skill::getId)
                .collect(java.util.stream.Collectors.toSet());
        skills.stream()
                .filter(s -> !existingIds.contains(s.getId()))
                .forEach(workflow.getActivatedSkills()::add);

        return workflowRepository.save(workflow);
    }

    @Transactional
    public Workflow deactivateSkill(String workflowId, String skillId) {
        Workflow workflow = findById(workflowId);
        workflow.getActivatedSkills().removeIf(s -> s.getId().equals(skillId));
        return workflowRepository.save(workflow);
    }
}
