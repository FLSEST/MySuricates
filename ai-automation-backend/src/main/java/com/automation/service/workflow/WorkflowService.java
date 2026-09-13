package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.ai.ReasoningEffort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    private final WorkflowVersionService workflowVersionService;
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
    public Workflow create(CreateWorkflowRequest request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", request.projectId()));
        BlockNameRules.rejectReservedNames(request.blocks());

        Workflow workflow = Workflow.builder()
                .project(project)
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .blocks(request.blocks() != null ? request.blocks() : new ArrayList<>())
                .instructions(request.instructions())
                .model(request.model())
                .provider(request.provider())
                .effort(normalizeEffort(request.effort()))
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
        eventPublisher.publishEvent(new WorkflowContentChangedEvent(saved.getId()));
        return saved;
    }

    @Transactional
    public Workflow update(String id, UpdateWorkflowRequest request) {
        Workflow workflow = findById(id);

        // Snapshot before update (only if execution-relevant fields changed)
        WorkflowSnapshot incomingSnapshot = computeIncomingSnapshot(workflow, request);
        String changeSource = request.changeSource() != null ? request.changeSource() : "user";
        workflowVersionService.snapshotBeforeUpdate(workflow, incomingSnapshot, changeSource, request.changeMessage());

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
            BlockNameRules.rejectReservedNames(request.blocks());
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
        if (request.effort() != null) {
            workflow.setEffort(normalizeEffort(request.effort()));
        }
        if (request.metadata() != null) {
            Map<String, Object> merged = new HashMap<>(workflow.getMetadata());
            merged.putAll(request.metadata());
            workflow.setMetadata(merged);
        }
        if (request.defaultContext() != null) {
            workflow.setDefaultContext(new HashMap<>(request.defaultContext()));
        }
        if (request.tags() != null) {
            // User-provided tags lock the workflow from auto-regeneration.
            workflow.setTags(new ArrayList<>(request.tags()));
            workflow.setTagsUserEdited(true);
            workflow.setTagsGeneratedAt(java.time.Instant.now());
        }
        if (request.category() != null) {
            workflow.setCategory(request.category());
            workflow.setTagsUserEdited(true);
        }

        boolean contentChanged = request.name() != null
                || request.description() != null
                || request.blocks() != null;

        Workflow saved = workflowRepository.save(workflow);
        log.info("Updated workflow: {} ({})", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow updated: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
        if (contentChanged) {
            eventPublisher.publishEvent(new WorkflowContentChangedEvent(saved.getId()));
        }
        return saved;
    }

    @Transactional
    public Workflow restoreFromSnapshot(String id, WorkflowSnapshot snapshot, String changeMessage) {
        Workflow workflow = findById(id);

        // Snapshot current state before restore
        workflowVersionService.snapshotBeforeUpdate(workflow, snapshot, "user", changeMessage);

        // Set ALL versioned fields unconditionally (including null)
        workflow.setBlocks(snapshot.blocks() != null ? snapshot.blocks() : new ArrayList<>());
        workflow.setInstructions(snapshot.instructions());
        workflow.setModel(snapshot.model());
        workflow.setProvider(snapshot.provider());
        workflow.setEffort(snapshot.effort());
        workflow.setMetadata(snapshot.metadata() != null ? new HashMap<>(snapshot.metadata()) : new HashMap<>());
        workflow.setDefaultContext(snapshot.defaultContext() != null ? new HashMap<>(snapshot.defaultContext()) : new HashMap<>());

        Workflow saved = workflowRepository.save(workflow);
        log.info("Restored workflow: {} ({}) from snapshot", saved.getName(), saved.getId());
        activityLogService.info(LogCategory.SYSTEM, "Workflow restored: " + saved.getName(), "WorkflowService", saved.getId(), saved.getId());
        return saved;
    }

    private WorkflowSnapshot computeIncomingSnapshot(Workflow current, UpdateWorkflowRequest request) {
        List<BlockDefinition> blocks = request.blocks() != null ? request.blocks() : current.getBlocks();
        String instructions = request.instructions() != null ? request.instructions() : current.getInstructions();
        String model = request.model() != null ? request.model() : current.getModel();
        String provider = request.provider() != null ? request.provider() : current.getProvider();
        String effort = request.effort() != null ? normalizeEffort(request.effort()) : current.getEffort();

        Map<String, Object> metadata;
        if (request.metadata() != null) {
            metadata = new HashMap<>(current.getMetadata());
            metadata.putAll(request.metadata());
        } else {
            metadata = current.getMetadata();
        }

        Map<String, Object> defaultContext = request.defaultContext() != null
                ? request.defaultContext() : current.getDefaultContext();

        List<String> skillIds = current.getActivatedSkills().stream()
                .map(Skill::getId).toList();

        return new WorkflowSnapshot(blocks, instructions, model, provider, metadata, defaultContext, skillIds, effort);
    }

    /** Blank clears the level (null); invalid values are rejected with 400. */
    private static String normalizeEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return null;
        }
        return ReasoningEffort.parse(effort)
                .map(ReasoningEffort::cliValue)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid effort level: '" + effort + "' (valid: low, medium, high, xhigh)"));
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
