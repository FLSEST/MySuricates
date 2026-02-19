package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final SkillRepository skillRepository;

    @Transactional(readOnly = true)
    public List<Workflow> findAll() {
        return workflowRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Workflow> findByProjectId(String projectId) {
        return workflowRepository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Workflow findById(String id) {
        return workflowRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", id));
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
                .build();

        // Activate skills if provided
        if (request.activatedSkillIds() != null) {
            List<Skill> skills = skillRepository.findAllById(request.activatedSkillIds());
            workflow.setActivatedSkills(skills);
        }

        Workflow saved = workflowRepository.save(workflow);
        log.info("Created workflow: {} ({})", saved.getName(), saved.getId());
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

        Workflow saved = workflowRepository.save(workflow);
        log.info("Updated workflow: {} ({})", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void delete(String id) {
        Workflow workflow = findById(id);
        workflowRepository.delete(workflow);
        log.info("Deleted workflow: {} ({})", workflow.getName(), workflow.getId());
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
